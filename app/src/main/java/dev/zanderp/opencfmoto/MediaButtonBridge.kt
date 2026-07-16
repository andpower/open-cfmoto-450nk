// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
// Ported from the ionutradu252/open-cfmoto fork.
package dev.zanderp.opencfmoto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import dev.zanderp.opencfmoto.aa.AaInput

/**
 * Turns the bike's handlebar buttons into Android Auto navigation.
 *
 * The buttons never reach us over the PXC/Wi-Fi link. What DOES work, verified on the bike:
 *   • short press ▲/▼ → AVRCP **absolute volume** → we read the DIRECTION as a knob click.
 *   • hold enter → AVRCP PLAY/PAUSE passthrough → mapped to ENTER (select).
 * The dash only emits the transport keys once we look like a real player, which is why this class
 * takes audio focus, plays a silent track, publishes metadata and posts a MediaStyle notification.
 *
 * Volume is watched with a [ContentObserver] rather than a remote-volume `VolumeProvider`: the bike
 * sends absolute volume, so there is no volume KEY event to intercept.
 *
 * All of it is gated on [ButtonMode] — toggle off and volume/media behave completely normally.
 */
class MediaButtonBridge(private val context: Context, private val log: (String) -> Unit) {

    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val mediaAttrs by lazy {
        android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    private var volumeObserver: ContentObserver? = null
    /** Volume we hold the stream at while capturing, so there's always headroom up AND down. */
    private var pinnedVolume = -1
    /** One-shot guard: the dash only needs re-reading once per session. See [reassert]. */
    private var reasserted = false
    /** The user's own volume, restored when capture is turned off. */
    private var userVolume = -1
    private var focusRequest: AudioFocusRequest? = null
    private var silence: AudioTrack? = null

    fun start() {
        handler.post {
            try {
                val s = MediaSession(context, "OpenCfMoto")
                s.setCallback(callback)
                val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_REWIND
                // An active PLAYING session is what makes the system route media buttons to us.
                s.setPlaybackState(
                    PlaybackState.Builder().setActions(actions)
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f).build()
                )
                session = s
                instance = this
                val on = ButtonMode.isControlAa(context)
                if (on) takeMediaFocus()
                s.isActive = on
                s.setPlaybackToLocal(mediaAttrs)
                if (on) pinVolume()
                startVolumeObserver()
                log("[BTN] bridge ready — mode=${if (on) "control AA (media focus + volume hijacked)" else "control media"}")
                scheduleReassertWhenBikeUp()
            } catch (e: Exception) {
                log("[BTN] media session failed: $e")
            }
        }
    }

    /**
     * Re-announce ourselves to the dash once the bike is actually connected. We take media focus the
     * moment the service starts — before the bike's PXC link comes up. The dash reads the player's
     * capabilities when its AVRCP link forms and then never re-reads; this forces a re-read at the
     * right moment. Only while capture is on (music is paused anyway, so the brief focus drop is free).
     */
    private fun scheduleReassertWhenBikeUp() {
        if (reasserted) return
        val poll = object : Runnable {
            var waited = 0L
            override fun run() {
                if (reasserted || !ButtonMode.isControlAa(context)) return
                if (BikeLink.prober?.isStreaming == true) {
                    reasserted = true
                    handler.postDelayed({ reassert() }, REASSERT_SETTLE_MS)
                    return
                }
                waited += REASSERT_POLL_MS
                if (waited < REASSERT_GIVEUP_MS) handler.postDelayed(this, REASSERT_POLL_MS)
                else log("[BTN] no bike link within ${REASSERT_GIVEUP_MS / 1000}s — skipping media re-assert")
            }
        }
        handler.postDelayed(poll, REASSERT_POLL_MS)
    }

    private fun reassert() {
        if (!ButtonMode.isControlAa(context)) return
        try {
            log("[BTN] bike link up — re-asserting media focus so the dash re-reads our player")
            releaseMediaFocus()
            session?.isActive = false
            handler.postDelayed({
                try {
                    takeMediaFocus()
                    session?.isActive = true
                    pinVolume()
                    log("[BTN] media focus re-asserted")
                } catch (e: Exception) {
                    log("[BTN] re-assert (re-take) failed: $e")
                }
            }, REASSERT_GAP_MS)
        } catch (e: Exception) {
            log("[BTN] re-assert failed: $e")
        }
    }

    /**
     * Live toggle: grab (true) or release (false) the bike's buttons. Grabbing means genuinely
     * becoming the phone's active media app — Android hands the media buttons to exactly ONE app, so
     * this necessarily takes them off the music player (and pauses it) for as long as it's on.
     */
    fun setCaptureActive(on: Boolean) {
        handler.post {
            try {
                if (on) takeMediaFocus() else releaseMediaFocus()
                session?.isActive = on
                if (on) pinVolume() else unpinVolume()
                if (!on) selectDownAt = 0L   // drop any half-finished press when handing buttons back
                log("[BTN] capture ${if (on) "ON — bike buttons drive Android Auto (music pauses)" else "OFF — buttons control media/volume"}")
            } catch (e: Exception) {
                log("[BTN] setCaptureActive failed: $e")
            }
        }
    }

    /**
     * Become the phone's active media app, so the bike's buttons come to US. Winning the media-button
     * routing requires two things a real player has: audio focus (AUDIOFOCUS_GAIN — this pauses the
     * music player) and actual playback (hence a looping silent track).
     */
    private fun takeMediaFocus() {
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(mediaAttrs)
                .setOnAudioFocusChangeListener { /* we play silence; nothing to duck */ }
                .build()
            focusRequest = req
            val granted = audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            log("[BTN] audio focus ${if (granted) "granted" else "DENIED"}")
        } catch (e: Exception) {
            log("[BTN] audio focus failed: $e")
        }
        startSilence()
        publishMetadata()
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_FAST_FORWARD or
                        PlaybackState.ACTION_REWIND
                )
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build()
        )
        postMediaNotification()
    }

    /** Publish a fake "now playing" track over AVRCP so the dash treats us as a playing player. */
    private fun publishMetadata() {
        try {
            session?.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Android Auto control")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "◀ ▶ / ▲ ▼ navigate · Enter selects · hold Enter = voice")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "OpenCfMoto")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, TRACK_MS)
                    .build()
            )
        } catch (e: Exception) {
            log("[BTN] metadata failed: $e")
        }
    }

    /** Post a MediaStyle notification bound to our session so the system treats us as a media app. */
    private fun postMediaNotification() {
        val s = session ?: return
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(MEDIA_CHANNEL, "Bike button control", NotificationManager.IMPORTANCE_LOW)
            )
            val n = Notification.Builder(context, MEDIA_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Android Auto control")
                .setContentText("Bike buttons drive Android Auto")
                .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .build()
            nm.notify(MEDIA_NOTIF_ID, n)
            log("[BTN] media notification posted (registers us as a media player)")
        } catch (e: Exception) {
            log("[BTN] media notification failed: $e")
        }
    }

    private fun cancelMediaNotification() {
        try { context.getSystemService(NotificationManager::class.java).cancel(MEDIA_NOTIF_ID) } catch (_: Exception) {}
    }

    private fun releaseMediaFocus() {
        cancelMediaNotification()
        stopSilence()
        try { focusRequest?.let { audio.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
        focusRequest = null
    }

    /** A looping silent track: makes us a genuinely "playing" media app so we win button routing. */
    private fun startSilence() {
        if (silence != null) return
        try {
            val rate = 8000
            val frames = rate            // 1 s of silence, looped forever
            val zeros = ShortArray(frames)
            val t = AudioTrack.Builder()
                .setAudioAttributes(mediaAttrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(frames * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(zeros, 0, zeros.size)
            t.setLoopPoints(0, frames, -1)
            t.setVolume(0f)
            t.play()
            silence = t
        } catch (e: Exception) {
            log("[BTN] silent track failed: $e")
        }
    }

    private fun stopSilence() {
        try { silence?.pause(); silence?.flush(); silence?.release() } catch (_: Exception) {}
        silence = null
    }

    fun stop() {
        if (instance === this) instance = null
        stopVolumeObserver()
        unpinVolume()
        releaseMediaFocus()
        try { session?.isActive = false } catch (_: Exception) {}
        try { session?.release() } catch (_: Exception) {}
        session = null
    }

    // ── volume as a navigation source ────────────────────────────────────────────────────────────

    /**
     * Pin the stream volume to the middle while capturing. The bike sends AVRCP *absolute* volume, so
     * we read the DIRECTION of each change; parking at mid guarantees headroom both ways so every
     * press registers (at the ends, a press in the pinned direction produces no change → no event).
     */
    private fun pinVolume() {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (userVolume < 0) userVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            pinnedVolume = (max / 2).coerceIn(1, max - 1)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, pinnedVolume, 0)
            log("[BTN] volume pinned at $pinnedVolume/$max (headroom both ways)")
        } catch (e: Exception) {
            log("[BTN] pinVolume failed: $e")
        }
    }

    /** Stop pinning and give the user their volume back. */
    private fun unpinVolume() {
        pinnedVolume = -1
        if (userVolume >= 0) {
            try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, userVolume, 0) } catch (_: Exception) {}
            userVolume = -1
        }
    }

    /**
     * Watch the stream volume: the bike's up/down arrive as AVRCP absolute volume (no key event at
     * all), so the direction of the change IS the button press. Re-pin immediately afterwards.
     */
    private fun startVolumeObserver() {
        if (volumeObserver != null) return
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val now = try { audio.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (e: Exception) { return }
                if (!ButtonMode.isControlAa(context) || pinnedVolume < 0) return
                if (now == pinnedVolume) return   // our own re-pin, or nothing to do

                val jump = now - pinnedVolume            // signed, in Android volume steps
                val up = jump > 0
                val dir = if (up) "UP" else "DOWN"
                // Re-pin FIRST: the gesture handling below can take a while (BACK/HOME redraw the
                // dash), and until we re-pin, a follow-up press is measured from the wrong base.
                try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, pinnedVolume, 0) } catch (_: Exception) {}

                val double = kotlin.math.abs(jump) >= DOUBLE_TAP_STEPS
                // Volume UP = backward (previous item), DOWN = forward (next item) — same semantics as
                // the 800MT's ◀/▶ track keys, so both layouts drive the same gestures.
                val gesture = when {
                    up && double -> ButtonGesture.NAV_BACK_DOUBLE
                    up -> ButtonGesture.NAV_BACK
                    double -> ButtonGesture.NAV_FWD_DOUBLE
                    else -> ButtonGesture.NAV_FWD
                }
                log("[BTN] volume $dir ($pinnedVolume→$now, jump=$jump)${if (double) " ×2" else ""}")
                run(gesture)
            }
        }
        try {
            context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, obs)
            volumeObserver = obs
        } catch (e: Exception) {
            log("[BTN] volume observer failed: $e")
        }
    }

    private fun stopVolumeObserver() {
        volumeObserver?.let { try { context.contentResolver.unregisterContentObserver(it) } catch (_: Exception) {} }
        volumeObserver = null
    }

    // ── gesture → action dispatch ────────────────────────────────────────────────────────────────

    /** Run whatever [ButtonMap] says this gesture should do (read per press, so changes are live). */
    private fun run(gesture: ButtonGesture) {
        if (!ButtonMode.isControlAa(context)) return   // media mode: leave the buttons to music
        val action = ButtonMap.get(context, gesture)
        log("[BTN] ${gesture.label} → ${action.label}")
        perform(action)
    }

    private fun perform(action: ButtonAction) {
        when (action) {
            ButtonAction.NONE -> {}
            ButtonAction.KNOB_FORWARD -> AaVideoBridge.scrollSink?.invoke(+1)
            ButtonAction.KNOB_BACK -> AaVideoBridge.scrollSink?.invoke(-1)
            ButtonAction.SELECT -> key(AaInput.KEY_ENTER)
            ButtonAction.BACK -> key(AaInput.KEY_BACK)
            ButtonAction.HOME -> key(AaInput.KEY_HOME)
            ButtonAction.ASSISTANT -> key(AaInput.KEY_ASSISTANT)
            ButtonAction.DPAD_UP -> key(AaInput.KEY_UP)
            ButtonAction.DPAD_DOWN -> key(AaInput.KEY_DOWN)
            ButtonAction.DPAD_LEFT -> key(AaInput.KEY_LEFT)
            ButtonAction.DPAD_RIGHT -> key(AaInput.KEY_RIGHT)
            ButtonAction.NAV_1 -> navigate(0)
            ButtonAction.NAV_2 -> navigate(1)
            ButtonAction.NAV_3 -> navigate(2)
        }
    }

    private fun key(code: Int) {
        val sink = AaVideoBridge.keySink
        if (sink == null) log("[BTN] no Android Auto session — key $code dropped") else sink(code)
    }

    private fun navigate(slot: Int) {
        NavLauncher.navigate(context, SavedPlaces.query(context, slot), log)
    }

    // ── where gestures come from ─────────────────────────────────────────────────────────────────

    private val callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            @Suppress("DEPRECATION")
            val ke = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return true
            when (ke.action) {
                KeyEvent.ACTION_DOWN -> {
                    val repeat = if (ke.repeatCount > 0) " repeat=${ke.repeatCount}" else ""
                    log("[BTN] media key ${KeyEvent.keyCodeToString(ke.keyCode)} down (code=${ke.keyCode})$repeat")
                    onKeyDown(ke.keyCode)
                }
                KeyEvent.ACTION_UP -> onKeyUp(ke.keyCode)
            }
            return true
        }
        // Fallbacks: the bike takes the raw-key path above; other dashes / BT remotes may dispatch
        // here. These carry no hold timing, so they can only ever be a short press.
        override fun onPlay() = run(ButtonGesture.SELECT_PRESS)
        override fun onPause() = run(ButtonGesture.SELECT_PRESS)
    }

    /** elapsedRealtime of the select button's key-down while it's held; 0 when nothing is pressed. */
    private var selectDownAt = 0L

    private fun isSelectKey(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE -> true
        else -> false
    }

    /**
     * Map a raw media key-down to a gesture:
     *   • play/pause → the enter/OK button. We don't fire yet: a tap is [ButtonGesture.SELECT_PRESS]
     *     and a hold is [ButtonGesture.SELECT_LONG], and we can only tell them apart once the button is
     *     released (see [onKeyUp]). We just stamp when it went down (ignoring auto-repeat downs).
     *   • next-/previous-track → the ▶/◀ presses of the 800MT's 5-way joystick (verified on hardware:
     *     right = KEYCODE_MEDIA_NEXT, left = KEYCODE_MEDIA_PREVIOUS). The 3-button CFDL16 dashes never
     *     emit these, so wiring them up is harmless there. These fire on down (they have no long-press).
     * Anything else is logged and dropped — aliasing an unknown key onto a real action would be a
     * nasty surprise when that action is "navigate home".
     */
    private fun onKeyDown(keyCode: Int) {
        when {
            isSelectKey(keyCode) -> { if (selectDownAt == 0L) selectDownAt = SystemClock.elapsedRealtime() }
            keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> run(ButtonGesture.NAV_FWD)
            keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                keyCode == KeyEvent.KEYCODE_MEDIA_REWIND -> run(ButtonGesture.NAV_BACK)
        }
    }

    /**
     * Select button released: how long it was held decides the gesture. Held past [LONG_PRESS_MS] →
     * [ButtonGesture.SELECT_LONG], otherwise a normal [ButtonGesture.SELECT_PRESS]. An UP with no
     * matching DOWN (e.g. capture turned on mid-press) falls back to a short press so select never
     * silently does nothing.
     */
    private fun onKeyUp(keyCode: Int) {
        if (!isSelectKey(keyCode)) return
        val downAt = selectDownAt
        selectDownAt = 0L
        if (downAt == 0L) { run(ButtonGesture.SELECT_PRESS); return }
        val heldMs = SystemClock.elapsedRealtime() - downAt
        val long = heldMs >= LONG_PRESS_MS
        log("[BTN] select held ${heldMs}ms → ${if (long) "long press" else "tap"}")
        run(if (long) ButtonGesture.SELECT_LONG else ButtonGesture.SELECT_PRESS)
    }

    companion object {
        /** Duration of the fake track we advertise, so the dash sees a normal "now playing". */
        private const val TRACK_MS = 3_600_000L

        /**
         * How big a volume jump means the rider double-tapped rather than tapped once. A double-tap
         * does NOT arrive as two events — the bike coalesces the presses into a single AVRCP absolute
         * volume, so the *size* of the jump is the press count. The jump is logged on every press: if
         * a dash calibrates differently, that log line is what to re-read.
         */
        private const val DOUBLE_TAP_STEPS = 3

        /**
         * How long the select button must be held to count as a long press rather than a tap. A touch
         * above Android's own 500 ms long-press timeout, to leave margin for a gloved, deliberate hold
         * and keep ordinary taps from tripping it. The actual hold is logged on every release, so this
         * is the number to re-read if a dash times differently.
         */
        private const val LONG_PRESS_MS = 600L

        private const val REASSERT_POLL_MS = 1_000L
        private const val REASSERT_GIVEUP_MS = 90_000L
        /** Let the dash's AVRCP link settle after the bike connects before poking it. */
        private const val REASSERT_SETTLE_MS = 3_000L
        /** Long enough that the drop and re-take read as two events, not a no-op. */
        private const val REASSERT_GAP_MS = 500L
        private const val MEDIA_CHANNEL = "opencfmoto_media"
        private const val MEDIA_NOTIF_ID = 3   // must not collide with AndroidAutoService's NOTIF_ID (2)

        /** The live bridge (when Android Auto is running), so the settings toggle can reach it. */
        @Volatile var instance: MediaButtonBridge? = null
            private set
    }
}
