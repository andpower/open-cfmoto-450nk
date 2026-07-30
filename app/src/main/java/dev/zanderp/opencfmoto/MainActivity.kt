// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logPanel: View
    private lateinit var tipsPanel: View
    private lateinit var statusView: TextView
    private lateinit var statusIcon: android.widget.ImageView
    private lateinit var statusProgress: View
    private lateinit var bikeView: TextView
    private lateinit var connectBtn: Button
    private lateinit var toggleLogBtn: Button
    private lateinit var prober: EasyConnProber
    private var bleWakeUp: BleWakeUp? = null
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    /** True when the pending QR scan should kick off the Android Auto flow (vs the mirror path). */
    private var pendingAaStart = false
    /** Explicit installed app selected from [AppsActivity] for whole-screen parked projection. */
    private var pendingAppPackage: String? = null
    private var pendingAppClass: String? = null
    private var pendingAppLabel: String? = null
    /** Guards the "close the official CFMoto app" prompt so it shows once per error, not every redraw. */
    private var rivalPromptShown = false
    /** Guards the VPN kill-switch prompt so it shows once per error, not every redraw. */
    private var vpnPromptShown = false

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val raw = result.data?.getStringExtra(QrScanActivity.RESULT_QR)
        if (result.resultCode != RESULT_OK || raw == null) {
            log("QR scan cancelled")
            if (pendingAppPackage != null) {
                cancelProjection()
                clearPendingApp()
            }
            return@registerForActivityResult
        }
        log("QR raw: $raw")
        val qr = QrData.parse(raw)
        if (qr == null) {
            log("QR parse FAILED — missing ssid/pwd?")
            Toast.makeText(this, uiText("Invalid QR"), Toast.LENGTH_SHORT).show()
            if (pendingAppPackage != null) {
                cancelProjection()
                clearPendingApp()
            }
            return@registerForActivityResult
        }
        log(
            "QR parsed: ssid=${qr.ssid} mac=${qr.mac} action=${qr.action} " +
                "(ap=${qr.supportsAp}, p2p=${qr.supportsP2p}) modelId=${qr.modelId} sn=${qr.sn}"
        )
        // Remember this bike so the next ride is a one-tap reconnect (skips the scan entirely).
        BikeMemory.save(this, raw, qr)
        refreshBikeLabel()

        if (pendingAaStart) {
            pendingAaStart = false
            startAaFlow(qr)
        } else {
            // Mirror path (screen projection already armed): connect straight away.
            applyProfile(qr)
            ConnectionState.set(Phase.MIRRORING, BikeMemory.lastBikeName(this) ?: qr.ssid)
            joinWifi(qr, gateOnAaSteady = false)
            if (pendingAppPackage != null) {
                launchPendingMirrorApp()
            } else {
                // Same as startMirrorLink: single-app capture needs the shared app visible.
                moveTaskToBack(true)
                Toast.makeText(
                    this,
                    uiText("Leave the shared app on screen (OpenCfMoto stays in the notification)"),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private val appsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            log("app selection cancelled")
            clearPendingApp()
            return@registerForActivityResult
        }
        val data = result.data!!
        val packageName = data.getStringExtra(AppsActivity.EXTRA_PACKAGE).orEmpty()
        val className = data.getStringExtra(AppsActivity.EXTRA_CLASS).orEmpty()
        val label = data.getStringExtra(AppsActivity.EXTRA_LABEL).orEmpty()
        val component = if (packageName.isNotBlank() && className.isNotBlank()) {
            ComponentName(packageName, className)
        } else {
            null
        }
        val activity = component?.let {
            runCatching { packageManager.getActivityInfo(it, 0) }.getOrNull()
        }
        if (component == null || activity == null || !activity.enabled || packageName == this.packageName) {
            log("app selection invalid: package='$packageName' class='$className'")
            Toast.makeText(this, uiText("That app cannot be launched"), Toast.LENGTH_LONG).show()
            clearPendingApp()
            return@registerForActivityResult
        }
        pendingAppPackage = packageName
        pendingAppClass = className
        pendingAppLabel = label.ifBlank { packageName }
        confirmParkedAndStartApp()
    }

    /** Pick the bike profile from the QR up front — it drives the Android Auto resolution/orientation,
     *  which must be set before AA starts. CLIENT_INFO refines it later during the PXC handshake. */
    private fun applyProfile(qr: QrData) {
        AppSettings.applyToHolder(this)
        BikeProfileHolder.active = BikeProfiles.selectByQr(qr, this)
        DashMemory.setLastDashTouch(this, BikeProfileHolder.advertisesScreenTouch)
        val userOverride = VideoPrefs.resolutionOverride(this, BikeProfileHolder.active)
        // In AUTO mode, if a previous session revealed this dash is a different orientation than the
        // profile assumes, flip AA to match. Learned from the dash's REQ_CONFIG_CAPTURE (see DashMemory).
        val autoGeo = if (userOverride == null) DashMemory.specFor(this, qr.ssid, BikeProfileHolder.active) else null
        BikeProfileHolder.aaVideoOverride = userOverride ?: autoGeo
        val spec = BikeProfileHolder.aaVideo
        BikeProfileHolder.aaContentMargins = VideoPrefs.aaMarginsFor(this, spec, qr.ssid)
        val aspectMode = VideoPrefs.matchAspectMode(this)
        val aspectNote = BikeProfileHolder.aaContentMargins.let { m ->
            when {
                m.any -> " [match-aspect ${aspectMode.name}: margins ${m.marginW}x${m.marginH}]"
                aspectMode == MatchAspectMode.OFF -> " [match-aspect OFF]"
                VideoPrefs.detectedPanelSize(this) == null -> " [match-aspect: waiting for panel size]"
                else -> " [match-aspect ${aspectMode.name}: margins 0]"
            }
        }
        val note = when {
            userOverride != null -> " (override: ${VideoPrefs.resolution(this).label})"
            autoGeo != null -> " (auto-orientation from last connect)"
            else -> ""
        }
        val touchNote = when {
            BikeProfileHolder.forceNonTouch -> " [Disable touchscreen ON — focus/knob AA]"
            BikeProfileHolder.forceTouch -> " [Force touchscreen ON — touch AA]"
            else -> ""
        }
        val ov = BikeProfileHolder.profileOverride
        val ovNote = if (ov != ProfileOverride.AUTO) " [profile override: ${ov.shortLabel}]" else ""
        log("→ bike profile (QR ssid=${qr.ssid} modelId=${qr.modelId}): ${BikeProfileHolder.active.name} " +
            "→ AA ${spec.width}x${spec.height} @${spec.dpi}dpi$note$touchNote$ovNote$aspectNote")
    }

    /** Start the Android Auto → bike projection for [qr]. Shared by the one-tap Connect reconnect
     *  and a fresh scan, so both paths behave identically. */
    private fun startAaFlow(qr: QrData) {
        try {
            if (!WifiGate.ensureEnabledOrPrompt(this)) return
            applyProfile(qr)
            val bikeName = BikeMemory.lastBikeName(this) ?: qr.ssid
            ConnectionState.set(Phase.STARTING_AA, bikeName)
            log("→ starting Android Auto receiver (loopback self-mode). Ensure Android Auto is installed & set up.")

            // Parallel startup: the two slow steps (AA reaching steady video, and the user accepting the
            // bike Wi-Fi dialog) now overlap. [BikeLink] gates the actual bike probe until BOTH complete,
            // so the bike is never contacted before AA has frames to serve. These callbacks fire against
            // process-global state (applicationContext + BikeLink.prober), NOT this activity: launching
            // Google AA can destroy/recreate MainActivity mid-startup and the hand-off must still finish.
            BikeLink.beginHandoff()
            AaVideoBridge.onSteadyVideo = {
                AaVideoBridge.onSteadyVideo = null
                ConnectionState.set(Phase.AA_VIDEO_LIVE)
                LogBus.log("→ Android Auto video is live")
                BikeLink.markAaVideoSteady()
            }
            AndroidAutoService.start(this)
            // Trigger Google AA to project from the FOREGROUND activity (background-activity-launch
            // safe on Android 12+/15), after giving the service's :5288 server time to bind.
            logView.postDelayed({
                try {
                    dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(this, log = ::log)
                } catch (e: Exception) {
                    log("AA self-mode trigger failed: $e")
                }
            }, 900)
            // Kick off the Wi-Fi join right away, in parallel with AA boot.
            joinWifi(qr, gateOnAaSteady = true)
        } catch (e: Exception) {
            log("Connect failed (app stays open): $e")
            CrashGuard.persistSession(this)
            ConnectionState.set(Phase.ERROR, "Connect failed — see Logs / Share Logs")
        }
    }

    /** After a fatal crash, tell the rider logs survived and keep Share Logs useful. */
    private fun maybeShowCrashRecovery() {
        if (CrashGuard.pendingCrashText(this) == null) return
        try {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(uiText("Recovered after a crash"))
                .setMessage(
                    uiText("OpenCfMoto closed unexpectedly last time. The log and crash report were saved — ") +
                        "open Logs and tap Share Logs so we can see what happened.",
                )
                .setPositiveButton(uiText("Share Logs")) { _, _ -> shareLog() }
                .setNegativeButton(uiText("Keep logs")) { _, _ -> }
                .setNeutralButton(uiText("Dismiss")) { _, _ -> CrashGuard.clearCrash(this) }
                .show()
        } catch (e: Exception) {
            log("crash recovery UI failed: $e")
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            log("screen-capture consent declined")
            clearPendingApp()
            return@registerForActivityResult
        }
        // FGS of type mediaProjection must be RUNNING before getMediaProjection() on API 34+.
        // startForegroundService is async, so poll the service's foreground flag (~every 100ms)
        // instead of guessing a fixed delay.
        ProjectionService.start(this)
        val code = result.resultCode
        val data = result.data!!
        val maxTries = 50  // 50 * 100ms = 5s ceiling
        val poll = object : Runnable {
            var tries = 0
            override fun run() {
                if (ProjectionService.isForeground) {
                    try {
                        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        ProjectionHolder.projection = mpm.getMediaProjection(code, data)
                        GpxSession.clear()
                        val selectedApp = pendingAppLabel
                        val captureMode = if (selectedApp == null) {
                            "pick Entire screen or a single app (Android 14+)"
                        } else {
                            "whole screen for $selectedApp"
                        }
                        log("screen-capture armed (FGS up after ${tries * 100}ms) — $captureMode")
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle(uiText(if (selectedApp == null) "Mirror ready" else "$selectedApp ready"))
                            .setMessage(
                                if (selectedApp == null) {
                                    "Entire screen — best for riding; phone stays awake while mirroring. " +
                                        "Uses Setup ▸ Screen fit (Fit = whole UI + bars).\n\n" +
                                        "Single app — that app must stay on screen; Android sends no frames " +
                                        "in the background. Prefer GPX / Tracks or Android Auto for pocket use.\n\n" +
                                        "Bike touch does not drive mirrored apps. Continue connects and " +
                                        "sends OpenCfMoto to the background."
                                } else {
                                    "OpenCfMoto will connect to the bike and open $selectedApp using " +
                                        "whole-screen sharing.\n\nThe 450NK is not touch. The handlebar " +
                                        "buttons will be switched to normal media control, so play/pause " +
                                        "and track changes work only if $selectedApp supports them.\n\n" +
                                        "Use this mode only while parked. Protected video may appear black."
                                },
                            )
                            .setPositiveButton(uiText(if (selectedApp == null) "Continue" else "Launch on bike")) { _, _ ->
                                if (selectedApp == null) startMirrorLink() else startAppMirrorLink()
                            }
                            .setNegativeButton(uiText("Cancel")) { _, _ ->
                                cancelProjection()
                                clearPendingApp()
                            }
                            .setCancelable(false)
                            .show()
                    } catch (e: Exception) {
                        log("getMediaProjection failed: $e")
                        ProjectionService.stop(this@MainActivity)
                        clearPendingApp()
                    }
                } else if (tries++ < maxTries) {
                    logView.postDelayed(this, 100)
                } else {
                    log("foreground service did not start within 5s — aborting mirror")
                    ProjectionService.stop(this@MainActivity)
                    clearPendingApp()
                }
            }
        }
        logView.post(poll)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.applyToHolder(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        logView = findViewById(R.id.log_view)
        logScroll = findViewById(R.id.log_scroll)
        logPanel = findViewById(R.id.log_panel)
        tipsPanel = findViewById(R.id.tips_panel)
        statusView = findViewById(R.id.status_view)
        statusIcon = findViewById(R.id.status_icon)
        statusProgress = findViewById(R.id.status_progress)
        bikeView = findViewById(R.id.bike_view)
        connectBtn = findViewById(R.id.btn_connect)
        toggleLogBtn = findViewById(R.id.btn_toggle_log)
        logView.movementMethod = ScrollingMovementMethod()

        // Icons are set here rather than in XML: in this AGP/compileSdk setup, library (res-auto)
        // attributes like app:icon don't resolve in layouts, so we assign them programmatically.
        (connectBtn as? MaterialButton)?.setIconResource(R.drawable.ic_power)
        findViewById<android.widget.TextView>(R.id.brand_version).text =
            uiText("v${BuildConfig.VERSION_NAME}")
        // These four share a narrow row: put the icon on TOP so each label gets the full width.
        // full width and isn't clipped (e.g. "Mirror" → "Mirro" when the icon sat inline).
        (findViewById<View>(R.id.btn_aa_start) as? MaterialButton)?.apply {
            setIconResource(R.drawable.ic_qr)
            iconGravity = MaterialButton.ICON_GRAVITY_TOP
            iconPadding = 2
            setPadding(0, paddingTop, 0, paddingBottom)
        }
        (findViewById<View>(R.id.btn_gpx) as? MaterialButton)?.apply {
            setIconResource(R.drawable.ic_place)
            iconGravity = MaterialButton.ICON_GRAVITY_TOP
            iconPadding = 2
            setPadding(0, paddingTop, 0, paddingBottom)
        }
        (findViewById<View>(R.id.btn_apps) as? MaterialButton)?.apply {
            setIconResource(R.drawable.ic_apps)
            iconGravity = MaterialButton.ICON_GRAVITY_TOP
            iconPadding = 2
            setPadding(0, paddingTop, 0, paddingBottom)
        }
        (findViewById<View>(R.id.btn_mirror_start) as? MaterialButton)?.apply {
            setIconResource(R.drawable.ic_cast)
            iconGravity = MaterialButton.ICON_GRAVITY_TOP
            iconPadding = 2
            setPadding(0, paddingTop, 0, paddingBottom)
        }
        (findViewById<View>(R.id.btn_aa_stop) as? MaterialButton)?.setIconResource(R.drawable.ic_stop)
        (findViewById<View>(R.id.btn_hud_view) as? MaterialButton)?.apply {
            setIconResource(R.drawable.ic_cast)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        }
        (findViewById<View>(R.id.btn_controls) as? MaterialButton)?.apply {
            setIconResource(R.drawable.ic_devices)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        }
        (findViewById<View>(R.id.btn_setup) as? MaterialButton)?.apply {
            setIconResource(R.drawable.ic_settings)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        }
        (findViewById<View>(R.id.btn_devices) as? MaterialButton)?.apply {
            setIconResource(R.drawable.ic_devices)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        }
        (findViewById<View>(R.id.btn_trip) as? MaterialButton)?.apply {
            setIconResource(R.drawable.ic_speed)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        }
        (toggleLogBtn as? MaterialButton)?.apply {
            setIconResource(R.drawable.ic_logs)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        }

        // All components (bike PXC, Android Auto receiver, video pipeline — including those
        // running in the foreground service) log through LogBus; mirror it into the view.
        LogBus.listener = { line ->
            runOnUiThread {
                logView.append("$line\n")
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
        // Application already hydrated a prior session/crash into LogBus — show it in the view.
        val prior = LogBus.snapshot()
        if (prior.isNotBlank()) {
            logView.text = prior
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        maybeShowCrashRecovery()
        // Soft dep check after auto-connect has had time to start — never race Connect.
        logView.postDelayed({ DependencyPrompt.showOnLaunchIfNeeded(this) }, 2500)
        // Opening the home screen must not resume a killed NAV_TO (looks like "Arrived at X").
        val startingGpx = intent?.getBooleanExtra(EXTRA_START_GPX, false) == true
        val projecting = ConnectionState.phase == Phase.STREAMING ||
            ConnectionState.phase == Phase.MIRRORING
        if (!startingGpx && !projecting) {
            GpxSession.abandonStaleNavigation("app open")
        }
        if (startingGpx) {
            intent.removeExtra(EXTRA_START_GPX)
            logView.post { beginGpxProjection() }
        }

        // Reflect the coarse connection state in the big status header (so users don't read the log).
        ConnectionState.listener = { phase, detail ->
            runOnUiThread { renderStatus(phase, detail) }
        }
        renderStatus(ConnectionState.phase, ConnectionState.detail)
        refreshBikeLabel()

        // Reuse the process-global prober if one already exists (e.g. this activity was recreated
        // while the Android Auto receiver kept running in the foreground service). Constructing a
        // fresh one here would orphan the running instance — leaking its sockets/threads and making
        // the Stop button operate on the wrong object. See [BikeLink].
        prober = BikeLink.prober ?: EasyConnProber(applicationContext, LogBus::log).also { BikeLink.prober = it }

        // Android 13+: request notification permission up front so the mediaProjection
        // foreground-service notification can be posted (some setups gate the FGS on it).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3,
            )
        }

        // Connect while idle; Stop while busy / projecting (merged former Stop button).
        connectBtn.setOnClickListener {
            val phase = ConnectionState.phase
            if (phase.busy || phase == Phase.STREAMING || phase == Phase.MIRRORING) {
                stopEverything()
                return@setOnClickListener
            }
            if (DependencyPrompt.showForConnect(this, forScan = false)) {
                log("→ Connect blocked — missing dependencies (see dialog)")
                return@setOnClickListener
            }
            val saved = BikeMemory.lastQr(this)
            if (saved != null) {
                log("→ Connect: reusing saved bike '${BikeMemory.lastBikeName(this)}' (no scan needed)")
                ProjectionHolder.projection = null
                GpxSession.clear()
                ensureLocationPermission()
                startAaFlow(saved)
            } else {
                log("→ Connect: no saved bike — scan the dash QR.")
                startAaScan()
            }
        }

        findViewById<Button>(R.id.btn_aa_start).setOnClickListener { startAaScan() }

        findViewById<Button>(R.id.btn_mirror_start).setOnClickListener { onMirrorPressed() }
        findViewById<View>(R.id.btn_apps).setOnClickListener {
            appsLauncher.launch(Intent(this, AppsActivity::class.java))
        }
        findViewById<View>(R.id.btn_gpx).setOnClickListener { onMapPressed() }
        findViewById<Button>(R.id.btn_aa_stop).setOnClickListener { stopEverything() }

        toggleLogBtn.setOnClickListener {
            val show = logPanel.visibility != View.VISIBLE
            logPanel.visibility = if (show) View.VISIBLE else View.GONE
            // The tips panel and the log panel share the flexible space, so only one shows at a time.
            tipsPanel.visibility = if (show) View.GONE else View.VISIBLE
            toggleLogBtn.text = uiText(if (show) "Hide logs" else "Logs")
        }

        findViewById<View>(R.id.btn_hud_view).setOnClickListener { startActivity(Intent(this, HudViewActivity::class.java)) }
        findViewById<View>(R.id.btn_controls).setOnClickListener { startActivity(Intent(this, ControlsActivity::class.java)) }
        findViewById<Button>(R.id.btn_navigate).setOnClickListener { navigateToTyped() }
        val destField = findViewById<android.widget.EditText>(R.id.et_destination)
        destField?.setOnEditorActionListener { _, _, _ ->
            navigateToTyped(); true
        }
        // Bike Search tap → focus this field + show the phone keyboard (Presentation has no IME).
        DashRemote.setTypeOnPhoneHandler {
            runOnUiThread {
                val field = findViewById<android.widget.EditText>(R.id.et_destination) ?: return@runOnUiThread
                try {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
                            ),
                    )
                } catch (_: Exception) {}
                field.requestFocus()
                field.post {
                    val imm = getSystemService(INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                    imm?.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
                Toast.makeText(this, uiText("Type here — results show on the bike"), Toast.LENGTH_SHORT).show()
            }
        }
        // Live suggestions on the dash while typing on the phone.
        destField?.addTextChangedListener(object : android.text.TextWatcher {
            private val debounce = android.os.Handler(android.os.Looper.getMainLooper())
            private val run = Runnable {
                val q = destField.text?.toString()?.trim().orEmpty()
                if (q.length >= 2 && DashRemote.isAvailable) DashRemote.submit(q)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                debounce.removeCallbacks(run)
                debounce.postDelayed(run, 350)
            }
        })
        findViewById<View>(R.id.btn_devices).setOnClickListener { GarageActivity.start(this) }

        findViewById<View>(R.id.btn_trip).setOnClickListener { TripActivity.start(this) }

        findViewById<Button>(R.id.btn_share_log).setOnClickListener { shareLog() }

        findViewById<Button>(R.id.btn_setup).setOnClickListener { SetupActivity.start(this) }
        findViewById<View>(R.id.brand_title).setOnClickListener { AboutActivity.start(this) }
        findViewById<View>(R.id.btn_about_page).setOnClickListener { AboutActivity.start(this) }
        findViewById<View>(R.id.btn_check_update).setOnClickListener { checkUpdateManual() }
        findViewById<View>(R.id.btn_problem_report).setOnClickListener { reportProblem() }
        findViewById<View>(R.id.btn_donate).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AboutActivity.URL_KOFI)))
            } catch (_: Exception) {
                Toast.makeText(this, uiText("Couldn't open donate link"), Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            LogBus.clear()
            logView.text = uiText("")
        }

        log("Ready. Tap Connect to project Android Auto to your dash.")

        // First launch: walk the user through the one-time prerequisites.
        try {
            if (!SetupActivity.hasSeen(this)) SetupActivity.start(this)
            else maybeAutoConnect()
            maybeResumeFromParked(intent)
        } catch (e: Exception) {
            log("startup failed (UI still up): $e")
            CrashGuard.persistSession(this)
        }
    }

    override fun onPause() {
        CrashGuard.persistSession(this)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeResumeFromParked(intent)
        if (intent.getBooleanExtra(EXTRA_START_GPX, false)) {
            intent.removeExtra(EXTRA_START_GPX)
            beginGpxProjection()
        }
    }

    override fun onResume() {
        super.onResume()
        // The active bike (and its name) may have changed in the Garage — reflect it on the label and
        // the Connect button.
        refreshBikeLabel()
        renderStatus(ConnectionState.phase, ConnectionState.detail)
        if (WifiGate.isWifiEnabled(this)) WifiGate.cancelNotification(this)
        // Retry auto-connect on resume: after finishing first-run setup, or once the bike's Wi-Fi
        // comes into range shortly after launch. Guarded so it only ever starts one attempt.
        if (SetupActivity.hasSeen(this)) maybeAutoConnect()
        maybeCheckUpdate()
        maybeResumeFromParked(intent)
    }

    /**
     * Guaranteed, BAL-safe resume after the service parked Android Auto (long bike outage). Re-launching
     * Google AA needs a foreground Activity, so when the rider taps the "Bike reconnected" notification
     * (or just opens the app while parked and the bike looks in range) we finish the resume here — this
     * `startActivity(gearhead)` is allowed because we're in the foreground.
     */
    private fun maybeResumeFromParked(intent: Intent?) {
        val explicit = intent?.getBooleanExtra(AndroidAutoService.EXTRA_RESUME, false) == true
        if (!explicit && !AndroidAutoService.isParked && ConnectionState.phase != Phase.WAITING_FOR_BIKE) return
        val saved = BikeMemory.lastQr(this) ?: return
        // On a plain open (not an explicit tap), only resume when the bike doesn't look clearly absent.
        if (!explicit && BikeWifi.isSsidInRange(this, saved.ssid) == false) return
        intent?.removeExtra(AndroidAutoService.EXTRA_RESUME)
        log("→ Resuming projection to '${BikeMemory.lastBikeName(this)}' from the foreground")
        autoConnectStarted = true
        AndroidAutoService.notifyForegroundResuming()
        ProjectionHolder.projection = null
        ensureLocationPermission()
        startAaFlow(saved)
    }

    /**
     * Auto-connect on launch: if the rider left the feature on, a bike is paired, Android Auto is
     * installed, nothing is already running, and the bike's Wi-Fi looks in range, kick off the same
     * one-tap Connect flow automatically. Fires at most once per process ([autoConnectStarted]) — but
     * only that success latches it, so a "not in range yet" launch is retried from [onResume] once the
     * bike's Wi-Fi appears. Never interrupts an active session or the setup wizard.
     */
    private fun maybeAutoConnect() {
        if (autoConnectStarted) return
        if (!AppSettings.autoConnect(this)) return
        if (AndroidAutoService.isRunning) return
        if (ConnectionState.phase.busy ||
            ConnectionState.phase == Phase.STREAMING ||
            ConnectionState.phase == Phase.MIRRORING) return
        val saved = BikeMemory.lastQr(this)
        if (saved == null) {
            logAutoConnectSkipOnce("no bike paired")
            return
        }
        if (!SetupHelper.isAndroidAutoInstalled(this)) {
            logAutoConnectSkipOnce("Android Auto not installed")
            return
        }
        if (!WifiGate.isWifiEnabled(this)) {
            // Don't latch autoConnectStarted — retry once the rider turns Wi‑Fi back on.
            WifiGate.ensureEnabledOrPrompt(this)
            logAutoConnectSkipOnce("phone Wi‑Fi is off — turn it on to connect")
            return
        }

        val inRange = BikeWifi.isSsidInRange(this, saved.ssid)
        if (inRange == false) {
            logAutoConnectSkipOnce("'${BikeMemory.lastBikeName(this)}' not in range — will retry when its Wi-Fi appears")
            return
        }

        // Committed to connecting — latch so an activity recreation (Google AA foregrounding) or a
        // later onResume doesn't fire a second attempt. Use the main looper (not the view) so a
        // dependency dialog can't cancel the delayed start with the view.
        autoConnectStarted = true
        val why = if (inRange == true) "Wi-Fi in range" else "range unknown — trying anyway"
        log("→ Auto-connect: '${BikeMemory.lastBikeName(this)}' ($why). Disable in Setup ▸ Startup.")
        ProjectionHolder.projection = null   // bike uses the AA pipeline, not mirror
        ensureLocationPermission()
        val activity = this
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                if (activity.isFinishing || activity.isDestroyed) {
                    // Recreated before we fired — let the new instance retry.
                    autoConnectStarted = false
                    return@postDelayed
                }
                if (!AndroidAutoService.isRunning && !ConnectionState.phase.busy) {
                    activity.startAaFlow(saved)
                }
            } catch (e: Exception) {
                LogBus.log("auto-connect failed (app stays open): $e")
                CrashGuard.persistSession(activity)
                ConnectionState.set(Phase.ERROR, "Auto-connect failed — see Logs")
            }
        }, 1200)
    }

    /** Log an auto-connect skip reason at most once per process, so onResume retries don't spam. */
    private fun logAutoConnectSkipOnce(reason: String) {
        if (autoConnectSkipLogged) return
        autoConnectSkipLogged = true
        log("→ Auto-connect idle: $reason.")
    }

    override fun onDestroy() {
        DashRemote.setTypeOnPhoneHandler(null)
        LogBus.listener = null
        ConnectionState.listener = null
        // When the Android Auto receiver service is running, the whole AA→bike chain (receiver +
        // encoder in the FGS, plus the Wi-Fi + prober in process globals) must OUTLIVE this activity:
        // launching Google Android Auto can destroy/recreate MainActivity mid-hand-off, and tearing
        // the bike down here is exactly what left the dash on a black screen (the pending
        // onSteadyVideo hand-off was cancelled before it could fire). Only tear down when AA is NOT
        // running — i.e. the mirror path or a genuine exit. Full teardown is the "Stop" button.
        if (!AndroidAutoService.isRunning) {
            AaVideoBridge.onSteadyVideo = null
            prober.stop()
            bleWakeUp?.stop()
            bleWakeUp = null
            ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
            ProjectionHolder.projection = null
            ProjectionService.stop(this)
            // NOTE: AndroidAutoService is intentionally NOT stopped here — it is a foreground service
            // meant to keep running when the phone is backgrounded/locked. Use "Stop Android Auto".
            BikeWifi.leave(this, ::log)
            BikeWifiP2p.stop(::log)
        }
        super.onDestroy()
    }

    /** Launch the QR scanner for the Android Auto path (profile is chosen from the scan result). */
    private fun startAaScan() {
        if (DependencyPrompt.showForConnect(this, forScan = true)) {
            log("→ Scan blocked — missing dependencies (see dialog)")
            return
        }
        log("→ Android Auto: scan the bike QR first so we pick the right screen profile.")
        pendingAaStart = true
        ProjectionHolder.projection = null   // bike uses the AA pipeline, not mirror
        ensureLocationPermission()
        try {
            scanLauncher.launch(Intent(this, QrScanActivity::class.java))
        } catch (e: Exception) {
            log("scan launch failed ($e)")
            pendingAaStart = false
        }
    }

    /** Update the big status header + Connect button label from a [ConnectionState] transition. */
    private fun renderStatus(phase: Phase, detail: String) {
        statusView.text = uiText(phase.label)
        bikeView.text = if (detail.isNotBlank()) detail else bikeLabelText()
        val color = when (phase) {
            Phase.STREAMING, Phase.MIRRORING -> ContextCompat.getColor(this, R.color.status_live)
            Phase.ERROR -> ContextCompat.getColor(this, R.color.status_error)
            else -> if (phase.busy) ContextCompat.getColor(this, R.color.status_busy)
                    else ContextCompat.getColor(this, R.color.status_idle)
        }
        statusView.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        statusIcon.setColorFilter(color)
        statusProgress.visibility = if (phase.busy) View.VISIBLE else View.GONE

        // The bike's link ports are held by the official CFMoto app — offer to close it (see
        // EasyConnProber's bind-conflict path). Show once per error so we don't nag on every redraw.
        if (phase == Phase.ERROR && detail.contains("CFMoto app", ignoreCase = true)) {
            if (!rivalPromptShown) { rivalPromptShown = true; promptCloseRival() }
        } else {
            rivalPromptShown = false
        }
        // Only a confirmed kill-switch with a live internet VPN — Map↔AA blips can leave a stale
        // "kill-switch" detail without an actual VPN; don't nag in that case.
        if (phase == Phase.ERROR &&
            detail.contains("kill-switch", ignoreCase = true) &&
            BikeWifi.isVpnActive(this)
        ) {
            if (!vpnPromptShown) { vpnPromptShown = true; promptVpnKillSwitch() }
        } else {
            vpnPromptShown = false
        }
        connectBtn.text = uiText(when {
            phase.busy -> "Stop"
            phase == Phase.STREAMING || phase == Phase.MIRRORING -> "Stop"
            BikeMemory.hasSaved(this) -> "Connect to ${BikeMemory.lastBikeName(this)}"
            else -> "Connect"
        })
        (connectBtn as? MaterialButton)?.setIconResource(
            if (phase.busy || phase == Phase.STREAMING || phase == Phase.MIRRORING) {
                R.drawable.ic_stop
            } else {
                R.drawable.ic_power
            },
        )
        updateButtonStates(phase)
    }

    /** Enable actions that make sense in the current [phase]. */
    private fun updateButtonStates(phase: Phase) {
        val live = phase == Phase.STREAMING || phase == Phase.MIRRORING
        val busy = phase.busy
        // Connect/Stop always tappable except pure idle race — Stop while busy/live, Connect otherwise.
        connectBtn.isEnabled = true
        // Scan only when not mid-flight.
        setEnabled(R.id.btn_aa_start, !busy)
        // Map / Mirror stay available while live so the rider can switch dash content.
        setEnabled(R.id.btn_mirror_start, !busy || live)
        setEnabled(R.id.btn_apps, !busy || live)
        setEnabled(R.id.btn_gpx, !busy || live)
        setEnabled(R.id.btn_aa_stop, busy || live)
    }

    private fun setEnabled(id: Int, enabled: Boolean) {
        findViewById<View>(id)?.let {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
    }

    /** Show which bike (if any) is remembered, under the status header. */
    private fun refreshBikeLabel() {
        bikeView.text = bikeLabelText()
    }

    /**
     * The official CFMoto app is holding the mirroring-link ports. Android won't let us silently
     * force-stop another app, so offer a best-effort background kill and a one-tap jump to its
     * App-info screen (guaranteed Force stop), then reconnect.
     */
    private fun promptCloseRival() {
        val installed = RivalClient.isInstalled(this)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(uiText("Official CFMoto app is in the way"))
            .setMessage(
                uiText("The official CFMoto/EasyConnect app is running and holding the bike's link ports, so ") +
                    "the dash stays blank. Close it, then reconnect.\n\n" +
                    "\"Close & retry\" tries to stop it for you; if the dash is still blank, use " +
                    "\"App settings\" and tap Force stop."
            )
            .setPositiveButton(uiText("Close & retry")) { _, _ ->
                val killed = RivalClient.closeBestEffort(this)
                log(if (killed) "→ asked Android to close the official CFMoto app; retrying…"
                    else "→ couldn't auto-close the official CFMoto app — open its settings to Force stop.")
                connectBtn.postDelayed({ reconnectSavedBike() }, 1500)
            }
            .setNeutralButton(uiText("App settings")) { _, _ ->
                if (!RivalClient.openAppInfo(this)) {
                    Toast.makeText(this, uiText("Couldn't open app settings"), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(uiText("Dismiss"), null)
            .setCancelable(true)
            .apply {
                if (!installed) {
                    setMessage(
                        uiText(
                            "Something is holding the bike's link ports (10920-10922). " +
                                "Close any other CFMoto/EasyConnect app and reconnect.",
                        ),
                    )
                }
            }
            .show()
    }

    /**
     * Always-on VPN with connection blocking prevents pinning sockets to bike Wi-Fi (EPERM).
     * Offer a shortcut into system VPN settings; the user must disable the kill-switch or VPN.
     */
    private fun promptVpnKillSwitch() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(uiText("VPN is blocking the bike"))
            .setMessage(
                uiText("Android Auto reached the phone, but a VPN kill-switch is blocking the bike Wi‑Fi ") +
                    "(Network.bindSocket → EPERM).\n\n" +
                    "Fix (any one):\n" +
                    "• Turn the VPN off for this ride\n" +
                    "• Disable Always-on VPN → \"Block connections without VPN\"\n" +
                    "• Allow LAN / local network in the VPN app (PCAPdroid, AdGuard, etc.)\n\n" +
                    "Then tap Connect again."
            )
            .setPositiveButton(uiText("VPN settings")) { _, _ ->
                try {
                    startActivity(Intent("android.net.vpn.SETTINGS"))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(this, uiText("Open Settings ▸ Network ▸ VPN"), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(uiText("Dismiss"), null)
            .setCancelable(true)
            .show()
    }

    /** Re-run the one-tap Connect for the saved bike (used after closing the rival app). */
    private fun reconnectSavedBike() {
        val saved = BikeMemory.lastQr(this) ?: return
        ProjectionHolder.projection = null
        ensureLocationPermission()
        startAaFlow(saved)
    }

    private fun bikeLabelText(): String {
        val name = BikeMemory.lastBikeName(this)
        return if (name != null) "Paired: $name — tap Connect to reconnect"
        else "No bike paired yet — tap Connect to scan the dash QR"
    }

    /**
     * Join the bike Wi-Fi and start the PXC prober.
     *
     * When [gateOnAaSteady] is true (Android Auto path), the prober start is handed to [BikeLink],
     * which waits until AA video is also steady — so this Wi-Fi join can run in parallel with AA
     * boot. When false (mirror path), the prober starts as soon as Wi-Fi is bound.
     *
     * Uses applicationContext + the process-global prober (not this activity) so the hand-off
     * completes even if the activity is destroyed/recreated after it was armed.
     */
    private fun joinWifi(qr: QrData, gateOnAaSteady: Boolean) {
        if (!WifiGate.ensureEnabledOrPrompt(this)) return
        ConnectionState.set(Phase.JOINING_WIFI)
        val transport = AppSettings.transport(this)
        val useP2p = when (transport) {
            WifiTransport.P2P -> true
            WifiTransport.AP -> false
            WifiTransport.AUTO -> qr.supportsP2p && !qr.supportsAp
        }
        if (useP2p) {
            joinWifiP2p(qr, gateOnAaSteady)
            return
        }
        BikeWifi.join(
            context = applicationContext,
            ssid = qr.ssid,
            psk = qr.pwd,
            onAvailable = { network ->
                WifiGate.cancelNotification(applicationContext)
                if (gateOnAaSteady) {
                    LogBus.log("→ bike Wi-Fi bound (waiting for AA video to go steady)")
                    BikeLink.markWifiReady(network)
                } else {
                    // Mirror path: no AA gating — go straight to the PXC flow. (BLE wake-up is not
                    // required for projection; runBleWakeUpThenProber() remains available if needed.)
                    ConnectionState.set(Phase.PXC_CONNECTING)
                    LogBus.log("→ Wi-Fi bound; starting EasyConn PXC flow …")
                    try {
                        (BikeLink.prober ?: prober).start(BikeWifi.currentNetwork)
                    } catch (e: Exception) {
                        LogBus.log("prober start failed: $e")
                    }
                }
            },
            onLost = { LogBus.log("bike network lost") },
            log = LogBus::log,
        )
    }

    /** Wi‑Fi Direct Group Owner path (CL‑C450 / some DIRECT- SSIDs). */
    private fun joinWifiP2p(qr: QrData, gateOnAaSteady: Boolean) {
        if (!WifiGate.ensureEnabledOrPrompt(this)) return
        LogBus.log("→ joining via Wi‑Fi Direct (P2P)")
        BikeWifiP2p.connect(
            context = applicationContext,
            qr = qr,
            onConnected = { bindIp, gatewayIp ->
                WifiGate.cancelNotification(applicationContext)
                if (gateOnAaSteady) {
                    LogBus.log("→ P2P bound (waiting for AA video); bike=${gatewayIp.hostAddress}")
                    BikeLink.markP2pReady(bindIp, gatewayIp)
                } else {
                    ConnectionState.set(Phase.PXC_CONNECTING)
                    LogBus.log("→ P2P bound; starting EasyConn PXC flow …")
                    try {
                        (BikeLink.prober ?: prober).start(
                            network = null,
                            gatewayOverride = gatewayIp,
                            bindIpOverride = bindIp,
                        )
                    } catch (e: Exception) {
                        LogBus.log("prober start failed: $e")
                    }
                }
            },
            onFailed = { reason ->
                LogBus.log("P2P join failed: $reason — falling back to AP join")
                if (!WifiGate.ensureEnabledOrNotify(applicationContext)) return@connect
                BikeWifi.join(
                    context = applicationContext,
                    ssid = qr.ssid,
                    psk = qr.pwd,
                    onAvailable = { network ->
                        WifiGate.cancelNotification(applicationContext)
                        if (gateOnAaSteady) BikeLink.markWifiReady(network)
                        else {
                            ConnectionState.set(Phase.PXC_CONNECTING)
                            try {
                                (BikeLink.prober ?: prober).start(network)
                            } catch (e: Exception) {
                                LogBus.log("prober start failed: $e")
                            }
                        }
                    },
                    onLost = { LogBus.log("bike network lost") },
                    log = LogBus::log,
                )
            },
            log = LogBus::log,
        )
    }

    private fun runBleWakeUpThenProber() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                ), 2,
            )
            // The user will need to tap Scan again after granting; keeping it simple for PoC.
            return
        }
        bleWakeUp?.stop()
        bleWakeUp = BleWakeUp(
            context = this,
            log = ::log,
            onUnlocked = {
                log("→ BLE wake-up OK; starting EasyConn prober …")
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
            onFailed = { reason ->
                log("BLE wake-up failed: $reason — TCP probe likely useless, starting anyway")
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
        ).also { it.start() }
    }

    private fun ensureLocationPermission() {
        // Some OEMs require fine location to associate via WifiNetworkSpecifier.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1,
            )
        }
    }

    /** Home search → OpenCfMoto Map hub (not Google Maps / Android Auto). */
    private fun navigateToTyped() {
        val field = findViewById<android.widget.EditText>(R.id.et_destination)
        val dest = field.text?.toString()?.trim().orEmpty()
        if (dest.isEmpty()) {
            Toast.makeText(this, uiText("Type a place to search on the map"), Toast.LENGTH_SHORT).show()
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(field.windowToken, 0)
        // 1) Our own map is projected to the bike → type into ITS search (results show on the dash).
        if (DashRemote.submit(dest)) {
            field.setText("")
            Toast.makeText(this, uiText("Searching \"$dest\" on the dash"), Toast.LENGTH_SHORT).show()
            return
        }
        // 2) Android Auto (Google Maps / Waze) is live → search/navigate there so it hits the bike.
        if (AaVideoBridge.pipeline != null && launchMapsSearch(dest)) {
            field.setText("")
            Toast.makeText(this, uiText("Sent \"$dest\" to Android Auto"), Toast.LENGTH_SHORT).show()
            return
        }
        // 3) Nothing projected → open our Map hub search on the phone.
        GpxActivity.startSearch(this, dest)
    }

    /** Fire a Google Maps navigation/search intent; while AA is connected it surfaces on the bike. */
    private fun launchMapsSearch(dest: String): Boolean {
        val enc = Uri.encode(dest)
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$enc"))
                .setPackage("com.google.android.apps.maps"),
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc"))
                .setPackage("com.google.android.apps.maps"),
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc")),
        )
        for (i in attempts) {
            if (runCatching { startActivity(i) }.isSuccess) return true
        }
        return false
    }

    private fun stopEverything() {
        log("→ stopping everything (Android Auto + bike)")
        try { AaVideoBridge.onSteadyVideo = null } catch (_: Exception) {}
        try { AndroidAutoService.stop(this) } catch (e: Exception) { log("AA stop: $e") }
        try { if (::prober.isInitialized) prober.stop() } catch (e: Exception) { log("prober stop: $e") }
        try { bleWakeUp?.stop() } catch (_: Exception) {}
        bleWakeUp = null
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        try { GpxSession.clear() } catch (_: Exception) {}
        try { ProjectionService.stop(this) } catch (e: Exception) { log("projection stop: $e") }
        try { BikeWifi.leave(this, ::log) } catch (e: Exception) { log("wifi leave: $e") }
        try { BikeWifiP2p.stop(::log) } catch (e: Exception) { log("p2p stop: $e") }
        AppMirrorSession.restoreButtons(this)
        clearPendingApp()
        ConnectionState.set(Phase.STOPPED, "")
    }

    /**
     * Map button always opens the Hub — pick free ride / route / search / offline there.
     * Starting a ride projects to the bike (no AA); a "See map on phone" button covers
     * off-bike navigation (e.g. walking back to a parked bike).
     */
    private fun onMapPressed() {
        // Don't carry a killed Campina/etc. NAV_TO into the hub → phone map.
        if (ConnectionState.phase != Phase.STREAMING && ConnectionState.phase != Phase.MIRRORING) {
            GpxSession.abandonStaleNavigation("map hub")
        }
        log("→ Map: open Hub")
        GpxActivity.start(this)
    }

    /**
     * Mirror button: if already projecting, arm screen capture and re-link as mirror;
     * otherwise start mirror flow (consent then connect).
     */
    private fun onMirrorPressed() {
        log("→ Mirror: cast phone screen to dash")
        clearPendingApp()
        pendingAaStart = false
        ensureLocationPermission()
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
            } else {
                Toast.makeText(
                    this,
                    uiText("Single-app mirror needs Android 14+. Whole screen will be used."),
                    Toast.LENGTH_LONG,
                ).show()
                mpm.createScreenCaptureIntent()
            }
            projectionLauncher.launch(intent)
        } catch (e: Exception) {
            log("mirror start failed ($e)")
        }
    }

    /** Validate that the rider is stopped, then request whole-screen capture for the chosen app. */
    private fun confirmParkedAndStartApp() {
        val label = pendingAppLabel ?: return
        val speed = ParkedSafety.recentSpeedKmh(this)
        if (ParkedSafety.isMoving(speed)) {
            val shown = speed?.toInt() ?: 0
            log("→ Apps blocked: device reports $shown km/h")
            Toast.makeText(
                this,
                uiText("Apps are parked-only. Current speed is about $shown km/h."),
                Toast.LENGTH_LONG,
            ).show()
            clearPendingApp()
            return
        }
        val speedNote = speed?.let { "Current reported speed: ${it.toInt()} km/h.\n\n" }.orEmpty()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(uiText("Confirm the bike is parked"))
            .setMessage(
                uiText("$speedNote$label is not a standard Android Auto app. It will be mirrored as a ") +
                    "phone screen and may require phone interaction.\n\nDo not use video or touch-only " +
                    "apps while riding.",
            )
            .setPositiveButton(uiText("I am parked")) { _, _ -> startAppProjection() }
            .setNegativeButton(uiText("Cancel")) { _, _ -> clearPendingApp() }
            .show()
    }

    /** Apps mode always captures the whole display so OpenCfMoto can launch the selected app itself. */
    private fun startAppProjection() {
        val label = pendingAppLabel ?: return
        log("→ Apps: prepare whole-screen projection for $label")
        pendingAaStart = false
        ensureLocationPermission()
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                mpm.createScreenCaptureIntent()
            }
            projectionLauncher.launch(intent)
        } catch (e: Exception) {
            log("app projection start failed ($e)")
            Toast.makeText(this, uiText("Screen sharing could not be started"), Toast.LENGTH_LONG).show()
            clearPendingApp()
        }
    }

    private fun shareLog() {
        try {
            CrashGuard.persistSession(this)
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "opencfmoto-$stamp.log")
            file.writeText(LogBus.snapshot())
            val uris = ArrayList<Uri>()
            uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))

            val crash = CrashGuard.crashFile(this)
            if (crash.exists() && crash.length() > 0L) {
                uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", crash))
                log("attaching crash report: ${crash.name} (${crash.length()} bytes)")
            }

            // Attach any diagnostic H.264 dumps (VideoPipeline writes these to <externalFiles>/video).
            val videoDir = File(getExternalFilesDir(null), "video")
            val dumps = videoDir.listFiles { f -> f.name.endsWith(".h264") }?.sortedBy { it.name } ?: emptyList()
            for (d in dumps) {
                uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", d))
                log("attaching video dump: ${d.name} (${d.length()} bytes)")
            }

            val send = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                type = if (uris.size > 1) "*/*" else "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "opencfmoto log $stamp")
                if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                else putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share log"))
            log("log saved: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            log("share failed: $e")
        }
    }

    private fun maybeCheckUpdate() {
        Thread {
            val release = try {
                UpdateChecker.check(this, manual = false)
            } catch (_: Exception) {
                null
            } ?: return@Thread
            runOnUiThread { showUpdateDialog(release) }
        }.start()
    }

    private fun checkUpdateManual() {
        Toast.makeText(this, uiText("Checking for update…"), Toast.LENGTH_SHORT).show()
        Thread {
            val release = UpdateChecker.check(this, manual = true)
            runOnUiThread {
                if (release == null) {
                    Toast.makeText(this, uiText("You're up to date (or offline)"), Toast.LENGTH_SHORT).show()
                } else {
                    showUpdateDialog(release)
                }
            }
        }.start()
    }

    private fun showUpdateDialog(release: UpdateChecker.Release) {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(uiText("Update ${release.version}"))
            .setMessage(ReleaseNotes.toSpanned(release.notes))
            .setPositiveButton(uiText("Download")) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)))
                } catch (_: Exception) {
                    Toast.makeText(this, uiText("Couldn't open download link"), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(uiText("Skip")) { _, _ -> UpdateChecker.skip(this, release.version) }
            .setNeutralButton(uiText("Later"), null)
            .show()
        // Headings/bullets/bold come from [ReleaseNotes]; make markdown links tappable too.
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod =
            LinkMovementMethod.getInstance()
    }

    private fun reportProblem() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val problem = android.widget.EditText(this).apply {
            hint = "What went wrong?"
            minLines = 3
            setPadding(pad, pad, pad, pad)
        }
        val model = android.widget.EditText(this).apply {
            hint = "Bike model (e.g. 800NK Advanced)"
            setText(BikeMemory.lastBikeName(this@MainActivity).orEmpty())
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val year = android.widget.EditText(this).apply {
            hint = "Year (optional)"
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(problem)
            addView(model)
            addView(year)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(uiText("Report a problem"))
            .setMessage(uiText("Builds a shareable report with diagnostics + recent log (secrets redacted unless enabled in Setup)."))
            .setView(box)
            .setPositiveButton(uiText("Share")) { _, _ ->
                shareProblemReport(
                    problem.text.toString(),
                    model.text.toString(),
                    year.text.toString(),
                )
            }
            .setNegativeButton(uiText("Cancel"), null)
            .show()
    }

    private fun shareProblemReport(problem: String, model: String, year: String) {
        try {
            val diagnostics = buildString {
                appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("profile=${BikeProfileHolder.active.name}")
                appendLine("override=${BikeProfileHolder.profileOverride}")
                appendLine("transport=${AppSettings.transport(this@MainActivity).label}")
                appendLine("margins=${ScreenMargins.summary()}")
                appendLine("forceNonTouch=${BikeProfileHolder.forceNonTouch}")
                appendLine("forceTouch=${BikeProfileHolder.forceTouch}")
                appendLine("ssid=${BikeMemory.lastQr(this@MainActivity)?.ssid ?: "—"}")
                appendLine("phase=${ConnectionState.phase} ${ConnectionState.detail}")
            }
            val text = ProblemReport.file(
                problem = problem.ifBlank { "(not specified)" },
                model = model,
                year = year,
                diagnostics = diagnostics,
                log = LogBus.snapshot(),
            )
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, "opencfmoto-report.txt")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, ProblemReport.subject(model, BuildConfig.VERSION_NAME))
                putExtra(Intent.EXTRA_TEXT, ProblemReport.body(problem, model, year, diagnostics))
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share problem report"))
        } catch (e: Exception) {
            Toast.makeText(this, uiText("Couldn't share report: $e"), Toast.LENGTH_LONG).show()
        }
    }

    private fun log(msg: String) = LogBus.log(msg)

    /** Connect the armed whole-screen projection, then put the selected installed app on top. */
    private fun startAppMirrorLink() {
        val label = pendingAppLabel ?: return
        val saved = BikeMemory.lastQr(this)
        if (saved != null) {
            log("→ Apps: reusing saved bike '${BikeMemory.lastBikeName(this)}' for $label")
            applyProfile(saved)
            ConnectionState.set(Phase.MIRRORING, BikeMemory.lastBikeName(this) ?: saved.ssid)
            joinWifi(saved, gateOnAaSteady = false)
            launchPendingMirrorApp()
        } else {
            log("→ Apps: scan the dash QR before launching $label…")
            scanLauncher.launch(Intent(this, QrScanActivity::class.java))
        }
    }

    private fun launchPendingMirrorApp() {
        val packageName = pendingAppPackage
        val className = pendingAppClass
        val label = pendingAppLabel ?: packageName
        if (packageName.isNullOrBlank() || className.isNullOrBlank()) {
            log("→ Apps failed: selected component was lost")
            Toast.makeText(this, uiText("The selected app was lost. Choose it again."), Toast.LENGTH_LONG).show()
            stopEverything()
            return
        }
        val component = ComponentName(packageName, className)
        val enabled = runCatching { packageManager.getActivityInfo(component, 0).enabled }
            .getOrDefault(false)
        if (!enabled) {
            log("→ Apps failed: $component is missing or disabled")
            Toast.makeText(this, uiText("$label is missing or disabled"), Toast.LENGTH_LONG).show()
            stopEverything()
            return
        }

        val changedMode = AppMirrorSession.useMediaButtons(this)
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            val mediaMode = if (changedMode) "media (temporary)" else "media"
            log("→ Apps: launched $label ($component); handlebar mode=$mediaMode")
            val drm = if (AppsCatalog.mayUseProtectedVideo(packageName)) {
                " Protected video may appear black."
            } else {
                ""
            }
            Toast.makeText(
                this,
                uiText("$label is on the bike. Handlebar media controls depend on the app.$drm"),
                Toast.LENGTH_LONG,
            ).show()
            clearPendingApp()
        } catch (e: Exception) {
            log("→ Apps launch failed for $component: $e")
            Toast.makeText(this, uiText("Couldn't open $label"), Toast.LENGTH_LONG).show()
            stopEverything()
        }
    }

    private fun clearPendingApp() {
        pendingAppPackage = null
        pendingAppClass = null
        pendingAppLabel = null
    }

    private fun cancelProjection() {
        ProjectionHolder.projection?.let { projection ->
            try { projection.stop() } catch (_: Exception) {}
        }
        ProjectionHolder.projection = null
        ProjectionService.stop(this)
    }

    /** After screen-capture consent: connect using saved bike or scan. */
    private fun startMirrorLink() {
        val saved = BikeMemory.lastQr(this)
        if (saved != null) {
            log("→ Mirror: reusing saved bike '${BikeMemory.lastBikeName(this)}'")
            applyProfile(saved)
            ConnectionState.set(Phase.MIRRORING, BikeMemory.lastBikeName(this) ?: saved.ssid)
            joinWifi(saved, gateOnAaSteady = false)
            // Single-app MediaProjection only emits while the shared app is visible. Leaving this
            // Activity on top → capture visible=false → black dash / framesSent=0.
            moveTaskToBack(true)
            Toast.makeText(
                this,
                uiText("Leave the shared app on screen (OpenCfMoto stays in the notification)"),
                Toast.LENGTH_LONG,
            ).show()
        } else {
            log("→ Mirror: scan the dash QR…")
            scanLauncher.launch(Intent(this, QrScanActivity::class.java))
        }
    }

    /**
     * Map / GPX session ready.
     * If already projecting to the bike → switch content over Wi‑Fi.
     * Otherwise open phone Dash preview (do not auto-join bike Wi‑Fi — that looked like “nothing”).
     */
    private fun beginGpxProjection() {
        if (!GpxSession.active) {
            log("→ Map: nothing prepared — open Map / GPX first")
            GpxActivity.start(this)
            return
        }
        ProjectionHolder.projection = null
        pendingAaStart = false
        ensureLocationPermission()
        val live = ConnectionState.phase == Phase.STREAMING ||
            ConnectionState.phase == Phase.MIRRORING
        val saved = BikeMemory.lastQr(this)
        if (live && saved != null) {
            log(
                "→ Map: ${GpxSession.mode} '${GpxSession.trackName}' → " +
                    "'${BikeMemory.lastBikeName(this)}'",
            )
            applyProfile(saved)
            ConnectionState.set(Phase.MIRRORING, BikeMemory.lastBikeName(this) ?: saved.ssid)
            joinWifi(saved, gateOnAaSteady = false)
            return
        }
        if (intent?.getBooleanExtra(EXTRA_GPX_TO_BIKE, false) == true && saved != null) {
            intent.removeExtra(EXTRA_GPX_TO_BIKE)
            log(
                "→ Map: project to bike ${GpxSession.mode} '${GpxSession.trackName}' → " +
                    "'${BikeMemory.lastBikeName(this)}'",
            )
            applyProfile(saved)
            ConnectionState.set(Phase.MIRRORING, BikeMemory.lastBikeName(this) ?: saved.ssid)
            joinWifi(saved, gateOnAaSteady = false)
            return
        }
        log("→ Map: phone Dash preview (${GpxSession.mode} '${GpxSession.trackName}')")
        HudViewActivity.startGpxPreview(this)
    }

    companion object {
        const val EXTRA_START_GPX = "start_gpx"
        /** When set with [EXTRA_START_GPX], join the bike even if not already live. */
        const val EXTRA_GPX_TO_BIKE = "gpx_to_bike"
        /** Latched once an auto-connect attempt actually starts, so it fires only once per process. */
        @Volatile private var autoConnectStarted = false
        /** So the "idle/not-in-range" reason is logged once, not on every onResume retry. */
        @Volatile private var autoConnectSkipLogged = false
    }
}
