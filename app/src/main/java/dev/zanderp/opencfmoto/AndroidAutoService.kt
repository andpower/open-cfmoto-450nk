// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import dev.zanderp.opencfmoto.aa.AaReceiver

/**
 * Foreground service that hosts the Android Auto receiver end-to-end (M4):
 *   VideoPipeline (H.264 encoder, external-source mode) + AaReceiver (loopback AAP receiver).
 *
 * Running as a foreground service with a partial wake lock keeps the whole decode→encode→PXC
 * chain alive while the phone is backgrounded or the screen is locked. The encoder's input
 * Surface is published to [AaVideoBridge] so [EasyConnProber] streams the re-encoded Android
 * Auto video to the bike dash.
 */
class AndroidAutoService : Service() {

    private var pipeline: VideoPipeline? = null
    private var receiver: AaReceiver? = null
    private var mediaButtons: MediaButtonBridge? = null
    private var hudStats: HudStatsProvider? = null
    private var wakeLock: PowerManager.WakeLock? = null
    // Wi-Fi locks that keep the bike link fast for the whole streaming session (see [acquireWifiLocks]).
    // Two are held on purpose: LOW_LATENCY only engages while the app is foreground AND the screen is
    // on, but riders are told to ride with the screen OFF — so HIGH_PERF (which holds regardless of
    // screen state) is the workhorse that keeps Wi-Fi out of power-save while pocketed, and LOW_LATENCY
    // adds extra latency reduction when the Dash view is open / riding screen-on.
    private var wifiLockLowLatency: WifiManager.WifiLock? = null
    private var wifiLockHighPerf: WifiManager.WifiLock? = null
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var lastRecoveryAt = 0L

    // Adaptive video tuning (PowerMode.AUTO): thermal + link-driven bitrate/fps, run each watchdog tick.
    private val adaptive by lazy { AdaptiveVideoController(applicationContext, LogBus::log) }

    // Hybrid reconnect supervisor state (see tickReconnectSupervisor / parkAa / doResume).
    @Volatile private var aaParked = false          // AA torn down to save battery while bike is gone
    @Volatile private var sawStream = false         // we reached STREAMING at least once this session
    @Volatile private var resumeSteadyReached = false
    private var wifiDownSince = 0L
    private var watchdogStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        active = this
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        startAsForeground()
        startReceiver()
        startWatchdog()
        isRunning = true
        return START_STICKY
    }

    /**
     * Auto-recovery watchdog. Every [WATCHDOG_TICK_MS] it checks the bike link and, if enabled:
     *  - STREAMING but no frames for [STALL_MS] → the dash stopped pulling (half-open link / stall):
     *    force a clean reconnect (drops sockets → the prober re-probes).
     *  - ERROR (reconnect budget spent) → periodically re-arm so a bike back in range re-links itself.
     * A cooldown between actions stops it from thrashing. The rider can disable it in Setup.
     */
    private fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        watchdogHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning) return
                try { tickReconnectSupervisor() } catch (_: Exception) {}
                try { tickWatchdog() } catch (_: Exception) {}
                try { adaptive.onTick(pipeline) } catch (_: Exception) {}
                try { tickTripLogging() } catch (_: Exception) {}
                watchdogHandler.postDelayed(this, WATCHDOG_TICK_MS)
            }
        }, WATCHDOG_TICK_MS)
    }

    /**
     * Auto-log the ride: once we're actually streaming to the dash, start the shared [TripRecorder]
     * (if the rider enabled trip logging and location is granted). The recorder is stopped and saved
     * when the session ends (user Exit → [onAaSessionEnded], or service teardown → [onDestroy]); a
     * transient RECONNECTING blip is left alone so a single ride isn't split by a brief drop.
     */
    private fun tickTripLogging() {
        val rec = TripLogger.current
        if (!AppSettings.logTrips(this)) {
            if (rec != null && rec.recording) rec.stopAndSave()
            return
        }
        if (ConnectionState.phase == Phase.STREAMING) {
            val r = TripLogger.get(this)
            if (!r.recording && r.start()) LogBus.log("[trip] auto-logging this ride")
        }
    }

    private fun tickWatchdog() {
        if (!AppSettings.autoRecovery(this)) return
        val prober = BikeLink.prober ?: return
        if (!prober.isRunning) return
        val now = System.currentTimeMillis()
        when {
            ConnectionState.phase == Phase.STREAMING &&
                prober.msSinceLastFrame() > STALL_MS &&
                now - lastRecoveryAt > ACTION_COOLDOWN_MS -> {
                LogBus.log("[watchdog] streaming stalled (${prober.msSinceLastFrame()}ms, no frames) — recovering")
                lastRecoveryAt = now
                prober.forceReconnect()
            }
            ConnectionState.phase == Phase.ERROR &&
                now - lastRecoveryAt > ERROR_COOLDOWN_MS -> {
                lastRecoveryAt = now
                prober.rearmFromError()
            }
        }
    }

    /**
     * Hybrid reconnect supervisor (the "stop AA + watch for the bike" behaviour).
     *
     * While Wi-Fi is up, brief drops are handled by the fast paths ([EasyConnProber] re-probe and
     * [BikeLink.onWifiReacquired]) — AA stays live for an instant resume. But if the bike's Wi-Fi has
     * been gone for [GRACE_MS] (rider stopped/parked the bike), we **park** AA: tear down the live
     * transcode to save battery/heat and let [BikeWifi] keep watching for the AP. When the AP returns
     * ([BikeWifi] re-acquires the network → [requestResume]) we rebuild AA and reconnect.
     */
    private fun tickReconnectSupervisor() {
        if (ConnectionState.phase == Phase.STREAMING) sawStream = true
        if (!AppSettings.autoRecovery(this)) return
        if (!isRunning || aaParked || !sawStream) return
        // Only park for a real Wi-Fi outage — a dash-only drop with Wi-Fi still up is the prober's job.
        if (BikeWifi.currentNetwork == null) {
            val now = System.currentTimeMillis()
            if (wifiDownSince == 0L) wifiDownSince = now
            else if (now - wifiDownSince > GRACE_MS) parkAa()
        } else {
            wifiDownSince = 0L
        }
    }

    /**
     * Tear AA down after the grace window so a parked/stopped bike doesn't cook the phone. Keeps the
     * foreground service alive (so [BikeWifi]'s network callback keeps watching for the AP) but drops
     * the wake lock so the phone can doze. Resumed by [doResume] when the bike's Wi-Fi comes back.
     */
    /** Stop the AA receiver + transcode (and any futile probing) but keep the service alive. */
    private fun tearDownAaKeepService() {
        AaVideoBridge.onSteadyVideo = null
        try { TripLogger.current?.stopAndSave() } catch (_: Exception) {}
        try { BikeLink.prober?.stop() } catch (_: Exception) {}
        try { mediaButtons?.stop() } catch (_: Exception) {}
        mediaButtons = null
        try { hudStats?.stop() } catch (_: Exception) {}
        hudStats = null
        HudBus.enabled = false
        try { receiver?.stop() } catch (_: Exception) {}
        receiver = null
        AaVideoBridge.pipeline = null
        try { pipeline?.stop() } catch (_: Exception) {}
        pipeline = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        releaseWifiLocks()   // parked: let the radio idle again while we wait for the bike
    }

    private fun parkAa() {
        if (aaParked) return
        aaParked = true
        LogBus.log("[AA] bike Wi-Fi gone > ${GRACE_MS / 1000}s — parking Android Auto to save battery; watching for the bike")
        ConnectionState.set(Phase.WAITING_FOR_BIKE, "will resume when the bike is back")
        tearDownAaKeepService()
        updateNotification(
            "Waiting for bike",
            "Projection paused to save battery — resumes when the bike is back",
        )
    }

    /**
     * The bike's Wi-Fi is back — rebuild the AA pipeline and reconnect. Re-triggering Google Android
     * Auto uses `startActivity`, which Android 12+ restricts from the background, so this is a
     * best-effort auto-resume; if AA doesn't come up in time we re-park (to keep battery low) and post
     * a tap-to-resume notification — [MainActivity] then finishes from the foreground (BAL-safe).
     */
    private fun doResume() {
        if (!aaParked) return
        aaParked = false
        wifiDownSince = 0L
        resumeSteadyReached = false
        LogBus.log("[AA] bike Wi-Fi back — resuming Android Auto (auto attempt)")
        // Reset the trailing detail (the park set it to "will resume…") back to the bike name so it
        // doesn't linger onto STREAMING as "Connected … — will resume when the bike is back".
        ConnectionState.set(Phase.STARTING_AA, BikeMemory.lastBikeName(applicationContext) ?: "")
        reacquireLocks()
        updateNotification("OpenCfMoto — Android Auto", "Reconnecting to the bike dash…")

        startReceiver()
        if (receiver == null) { resumeFailedFallback(); return }

        BikeLink.beginHandoff()
        AaVideoBridge.onSteadyVideo = {
            AaVideoBridge.onSteadyVideo = null
            resumeSteadyReached = true
            ConnectionState.set(Phase.AA_VIDEO_LIVE)
            LogBus.log("→ Android Auto video is live (resume)")
            BikeLink.markAaVideoSteady()
        }
        BikeLink.markWifiReady(BikeWifi.currentNetwork)
        dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(applicationContext, log = LogBus::log)

        // If AA can't self-start from the background (BAL), retry once then hand off to the foreground.
        watchdogHandler.postDelayed({
            if (!isRunning || aaParked || resumeSteadyReached) return@postDelayed
            LogBus.log("[AA] resume: no AA video yet — re-triggering self-mode once")
            dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(applicationContext, log = LogBus::log)
            watchdogHandler.postDelayed({
                if (!isRunning || aaParked || resumeSteadyReached) return@postDelayed
                resumeFailedFallback()
            }, RESUME_STEADY_TIMEOUT_MS)
        }, RESUME_STEADY_TIMEOUT_MS)
    }

    /** Background AA relaunch was blocked — park again (battery) and prompt the rider to tap to resume. */
    private fun resumeFailedFallback() {
        if (!isRunning || resumeSteadyReached) return
        aaParked = true
        LogBus.log("[AA] background AA relaunch blocked — parking + prompting tap-to-resume")
        ConnectionState.set(Phase.WAITING_FOR_BIKE, "tap to resume")
        tearDownAaKeepService()
        postResumeFallbackNotification()
    }

    /** (Re)acquire every lock the streaming session needs to stay alive backgrounded / screen-off. */
    private fun reacquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCfMoto:AndroidAuto")
                    .apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire(4 * 60 * 60 * 1000L)
        } catch (_: Exception) {}
        acquireWifiLocks()
    }

    /**
     * Hold Wi-Fi locks so the bike link doesn't drop into power-save mid-ride. A streaming session is
     * real-time video over the bike AP with the phone screen off — exactly when Android would otherwise
     * park the radio between beacons and add latency/jitter. HIGH_PERF keeps Wi-Fi awake regardless of
     * screen state (the screen-off case); LOW_LATENCY additionally cuts latency while foreground +
     * screen-on. Both are safe no-ops when Wi-Fi isn't the active transport. Released on park/stop so a
     * parked bike lets the radio idle again.
     */
    private fun acquireWifiLocks() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLockLowLatency == null) {
                wifiLockLowLatency = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "OpenCfMoto:LowLatency",
                ).apply { setReferenceCounted(false) }
            }
            if (wifiLockHighPerf == null) {
                @Suppress("DEPRECATION") // deprecated but still the reliable screen-off, no-power-save mode
                val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLockHighPerf = wm.createWifiLock(mode, "OpenCfMoto:HighPerf")
                    .apply { setReferenceCounted(false) }
            }
            var acquired = false
            if (wifiLockLowLatency?.isHeld != true) { wifiLockLowLatency?.acquire(); acquired = true }
            if (wifiLockHighPerf?.isHeld != true) { wifiLockHighPerf?.acquire(); acquired = true }
            if (acquired) LogBus.log("[AA] Wi-Fi locks held (high-perf + low-latency) — keeping the bike link hot")
        } catch (_: Exception) {}
    }

    private fun releaseWifiLocks() {
        try { if (wifiLockLowLatency?.isHeld == true) wifiLockLowLatency?.release() } catch (_: Exception) {}
        try { if (wifiLockHighPerf?.isHeld == true) wifiLockHighPerf?.release() } catch (_: Exception) {}
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Android Auto receiver", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    /** Content intent → open MainActivity; [resume] adds the flag that makes it re-project on open. */
    private fun buildNotification(title: String, text: String, resume: Boolean): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (resume) putExtra(EXTRA_RESUME, true)
        }
        val pi = PendingIntent.getActivity(this, if (resume) 1 else 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(title: String, text: String, resume: Boolean = false) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(title, text, resume))
        } catch (_: Exception) {}
    }

    /** Guaranteed BAL-safe resume path: prompt the rider to tap; [MainActivity] then re-projects. */
    private fun postResumeFallbackNotification() {
        LogBus.log("[AA] auto-resume couldn't relaunch Android Auto in the background — prompting to tap")
        val hint = if (!SetupHelper.canAutoResume(this))
            "Tap to resume — or enable “Display over other apps” in Setup for hands-free resume"
        else
            "Tap to resume projection to the dash"
        updateNotification("Bike reconnected", hint, resume = true)
    }

    private fun startAsForeground() {
        ensureChannel()
        val notification = buildNotification(
            "OpenCfMoto — Android Auto",
            "Receiving Android Auto for the bike dash — tap to open",
            resume = false,
        )

        // Include the location FGS type only when fine location is granted (auto-logging rides while
        // backgrounded needs it) — declaring it without the permission would crash on Android 14+.
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        var fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (hasLocation) fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, fgType)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        reacquireLocks()
        LogBus.log("[AA] foreground service up (wake + Wi-Fi locks held)")
    }

    private fun startReceiver() {
        if (receiver != null) { LogBus.log("[AA] receiver already started"); return }
        try {
            // Compositor mode: the AA decoder renders into the compositor's input surface; the
            // compositor letterboxes it into the bike's canvas (encoder created later, sized to the
            // bike's REQ_CONFIG_CAPTURE dims — see EasyConnProber / VideoPipeline.configureBikeCanvas).
            val vp = VideoPipeline(applicationContext, 0, 0, LogBus::log, compositor = true)
            vp.start()
            val surface = vp.decoderInputSurface()
            if (surface == null) {
                LogBus.log("[AA] compositor input surface null — cannot start receiver")
                vp.stop()
                stopSelf()
                return
            }
            pipeline = vp
            AaVideoBridge.pipeline = vp
            adaptive.reset()   // fresh session: start adaptation from the user's target
            receiver = AaReceiver(applicationContext, surface, LogBus::log).also {
                it.onSessionEnded = { userExit -> onAaSessionEnded(userExit) }
                it.start()
            }
            // Capture the bike's handlebar buttons (Bluetooth AVRCP) → Android Auto navigation.
            mediaButtons = MediaButtonBridge(applicationContext, LogBus::log).also { it.start() }
            // Rider HUD (B1): publish the toggle/unit/elements for the compositor and feed it live stats.
            HudBus.enabled = VideoPrefs.hudEnabled(applicationContext)
            HudBus.unit = VideoPrefs.speedUnit(applicationContext)
            HudBus.elements = VideoPrefs.hudElements(applicationContext)
            hudStats = HudStatsProvider(applicationContext, LogBus::log).also { it.start() }
        } catch (e: Exception) {
            LogBus.log("[AA] receiver start failed: $e")
            stopSelf()
        }
    }

    /**
     * The Android Auto session ended. If the rider tapped Exit in AA ([userExit]), fully stop
     * projection — otherwise the bike keeps pulling the compositor's last frame and the dash freezes
     * on a stale image while the app still looks "connected". A transient drop is left alone so the
     * receiver can accept a reconnect. Runs on the main thread to avoid tearing down from the AAP
     * poll thread that just fired this callback.
     */
    private fun onAaSessionEnded(userExit: Boolean) {
        if (!userExit) {
            LogBus.log("[AA] session dropped — receiver still listening for reconnect")
            return
        }
        LogBus.log("[AA] user exited Android Auto — stopping projection to the dash")
        watchdogHandler.post { fullTeardown() }
    }

    /**
     * Drop the whole projection: stop the bike link (dash stops pulling the compositor's last frame,
     * so no frozen image), leave the bike Wi-Fi, mark STOPPED, and stop the service (which tears down
     * the receiver, pipeline, and wake lock in [onDestroy]). Shared by the AA-exit path and app
     * close/kill ([onTaskRemoved]).
     */
    private fun fullTeardown() {
        AaVideoBridge.onSteadyVideo = null
        try { TripLogger.current?.stopAndSave() } catch (_: Exception) {}
        try { BikeLink.prober?.stop() } catch (_: Exception) {}
        try { BikeWifi.leave(applicationContext, LogBus::log) } catch (_: Exception) {}
        ConnectionState.set(Phase.STOPPED, "")
        stopSelf()   // onDestroy tears down the receiver, pipeline, and wake lock
    }

    /**
     * The user swiped the app away from recents (or the task was killed). A foreground service
     * survives task removal, which would leave Android Auto projecting a frozen dash. Tear the whole
     * thing down so closing/killing the app also closes AA + the projected maps on the HUD.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        LogBus.log("[AA] app closed/killed — stopping projection to the dash")
        try { fullTeardown() } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        aaParked = false
        if (active === this) active = null
        watchdogHandler.removeCallbacksAndMessages(null)
        try { TripLogger.current?.stopAndSave() } catch (_: Exception) {}
        try { mediaButtons?.stop() } catch (_: Exception) {}
        mediaButtons = null
        try { hudStats?.stop() } catch (_: Exception) {}
        hudStats = null
        HudBus.enabled = false
        try { receiver?.stop() } catch (_: Exception) {}
        receiver = null
        AaVideoBridge.pipeline = null
        try { pipeline?.stop() } catch (_: Exception) {}
        pipeline = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        releaseWifiLocks()
        wifiLockLowLatency = null
        wifiLockHighPerf = null
        LogBus.log("[AA] foreground service stopped")
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 2
        private const val CHANNEL_ID = "opencfmoto_androidauto"

        // Watchdog cadence / thresholds.
        private const val WATCHDOG_TICK_MS = 5_000L
        private const val STALL_MS = 8_000L            // no frames this long while STREAMING = stalled
        private const val ACTION_COOLDOWN_MS = 20_000L // min gap between forced reconnects
        private const val ERROR_COOLDOWN_MS = 30_000L  // min gap between re-arm attempts after ERROR
        private const val GRACE_MS = 60_000L           // keep AA alive this long after Wi-Fi drops
        private const val RESUME_STEADY_TIMEOUT_MS = 12_000L // wait for AA video before falling back

        /** Intent extra: MainActivity should re-project on open (BAL-safe resume after a park). */
        const val EXTRA_RESUME = "dev.zanderp.opencfmoto.RESUME_PROJECTION"

        @Volatile var isRunning = false
            private set

        // Running instance, so the Wi-Fi layer can ask a parked service to resume without an Intent.
        @Volatile private var active: AndroidAutoService? = null

        /** True while AA is parked (torn down, waiting for the bike's Wi-Fi to return). */
        val isParked: Boolean get() = active?.aaParked == true

        /** Ask a parked service to rebuild AA and reconnect (called by [BikeWifi] on AP re-acquire). */
        fun requestResume() {
            val svc = active ?: return
            svc.watchdogHandler.post { svc.doResume() }
        }

        /**
         * The foreground ([MainActivity]) is taking over the resume (it will rebuild AA itself), so drop
         * the parked flag and reacquire the locks — otherwise the supervisor/Wi-Fi hooks would fight
         * the foreground path.
         */
        fun notifyForegroundResuming() {
            active?.let { it.aaParked = false; it.wifiDownSince = 0L; it.reacquireLocks() }
        }

        const val ACTION_STOP = "dev.zanderp.opencfmoto.ACTION_STOP_AA"

        fun start(ctx: Context) {
            val i = Intent(ctx, AndroidAutoService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AndroidAutoService::class.java))
        }
    }
}
