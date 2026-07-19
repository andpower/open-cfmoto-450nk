package dev.zanderp.opencfmoto

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU fit/scale compositor for the Android Auto → bike video path.
 *
 * The AA video decoder renders into [inputSurface] (backed by a [SurfaceTexture]). Each decoded
 * frame is drawn — centered, on a black background — into the encoder's input surface (set later via
 * [setOutput], once the bike tells us its canvas size). This decouples the AA source resolution
 * (a fixed 16:9-ish landscape/portrait) from the differently-shaped bike canvas. The user's
 * [ScreenFit] preference chooses how: FILL (cover/crop), FIT (letterbox), or STRETCH (distort).
 *
 * [inputSurface] exists immediately (before the bike connects) so AA can reach steady video, which
 * is what triggers the bike hand-off in the first place. Until [setOutput] is called the render
 * thread just drains decoded frames (keeps AA flowing) without drawing anywhere.
 *
 * All GL work happens on a dedicated thread with the EGL context current. Based on the standard
 * SurfaceTexture→encoder pattern (Grafika).
 */
class AaCompositor(private val log: (String) -> Unit) {

    private val thread = HandlerThread("aa-compositor").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE   // keeps a current surface before output exists
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // Optional second render target: an in-app phone preview (HudViewActivity). The same decoded AA
    // frame is drawn — aspect-fit — into this surface so the rider can watch/drive the dash from the
    // phone. It's independent of the bike encoder output and works even before the bike links.
    private var previewSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    @Volatile private var previewW = 0
    @Volatile private var previewH = 0
    private var ppX = 0
    private var ppY = 0
    private var ppW = 0
    private var ppH = 0
    // AA source (decoder buffer) dims — the preview aspect-fits these; known from start(), independent
    // of whether the bike canvas (setOutput) is configured yet.
    @Volatile private var bufW = 0
    @Volatile private var bufH = 0

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var textureId = 0
    private lateinit var surfaceTexture: SurfaceTexture

    // ── Rider HUD (idea B1): paint telemetry into the FIT letterbox bars ──────────────────────────
    // A second, plain-2D GL program draws a Canvas-rendered bitmap (speed/trip/clock/battery) into
    // each detected black bar, on top of the AA frame, in the bike encoder surface only. Bars are
    // computed from the viewport in FIT mode (none in FILL/STRETCH); the bitmap is only re-rendered
    // and re-uploaded when a displayed value changes (~1 Hz), so it barely touches the idle throttle.
    private var hudProgram = 0
    private var hudAPos = 0
    private var hudATex = 0
    private var hudUTex = 0
    @Volatile private var hudBars: List<BarRect> = emptyList()
    private val hudTextures = HashMap<Int, Int>()   // bar index → GL texture id
    private val hudSigs = HashMap<Int, String>()    // bar index → last uploaded content signature
    // Quad with V flipped (Android bitmaps upload top-row-first, GL samples bottom-up).
    private val hudQuad: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 1f,
                 1f, -1f, 1f, 1f,
                -1f,  1f, 0f, 0f,
                 1f,  1f, 1f, 0f,
            ))
            position(0)
        }

    /** Where the AA decoder renders. Valid after [start]. */
    @Volatile var inputSurface: Surface? = null
        private set

    // Output canvas (bike) + source (AA) dims; viewport is derived from these.
    @Volatile private var canvasW = 0
    @Volatile private var canvasH = 0
    @Volatile private var srcW = 0
    @Volatile private var srcH = 0
    @Volatile private var vpX = 0
    @Volatile private var vpY = 0
    @Volatile private var vpW = 0
    @Volatile private var vpH = 0
    @Volatile private var fitMode: ScreenFit = ScreenFit.FILL

    private val texMatrix = FloatArray(16)

    // Frame pacing (battery): the compositor throttles live AA frames to [minFrameIntervalMs] (set
    // from the user's PowerMode) so we don't GPU-composite + hardware-encode + Wi-Fi-transmit more
    // often than needed. A frame that arrives too soon is coalesced (its texture is still latched)
    // and flushed by the keep-alive tick, so no motion is lost — we just cap the rate.
    @Volatile private var hasContent = false
    @Volatile private var pendingFrame = false
    private var lastDrawMs = 0L
    @Volatile private var minFrameIntervalMs = 42L  // default 24 fps; overridden by setFrameCap()

    // Keep-alive cadence. The tick is fast enough to promptly flush a coalesced trailing frame after
    // motion stops; the idle re-draw is deliberately slow — the bike only needs a frame every ~9s
    // (CLIENT_INFO socketTimeoutPeriodWifi) to stay connected, so re-encoding a *static* screen more
    // than ~twice a second is wasted heat. This is the big idle-power win over the old 15fps floor.
    private val KEEPALIVE_TICK_MS = 150L
    private val IDLE_REDRAW_MS = 2000L

    // Full-screen quad (triangle strip): pos.xy + tex.uv interleaved.
    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            ))
            position(0)
        }

    fun start() {
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try {
                initEgl()
                initGl()
                val spec = BikeProfileHolder.aaVideo
                bufW = spec.width; bufH = spec.height
                surfaceTexture = SurfaceTexture(textureId)
                surfaceTexture.setDefaultBufferSize(spec.width, spec.height)
                surfaceTexture.setOnFrameAvailableListener({ handler.post { onFrame() } }, handler)
                inputSurface = Surface(surfaceTexture)
                handler.postDelayed(keepAlive, KEEPALIVE_TICK_MS)
                log("[COMPOSITOR] ready (buffer size ${spec.width}x${spec.height}) — AA decoder input surface up (no output canvas yet)")
            } catch (e: Exception) {
                log("[COMPOSITOR] init failed: $e")
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    /** Point the compositor at the encoder's input surface, sized to the bike canvas. */
    fun setOutput(encoderSurface: Surface, cw: Int, ch: Int, sw: Int, sh: Int, fit: ScreenFit) {
        handler.post {
            try {
                if (windowSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, windowSurface)
                    windowSurface = EGL14.EGL_NO_SURFACE
                }
                val attrs = intArrayOf(EGL14.EGL_NONE)
                windowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface, attrs, 0)
                canvasW = cw; canvasH = ch; srcW = sw; srcH = sh; fitMode = fit
                computeViewport()
                log("[COMPOSITOR] output set canvas=${cw}x$ch src=${sw}x$sh fit=$fit → draw rect=${vpW}x$vpH @($vpX,$vpY)")
            } catch (e: Exception) {
                log("[COMPOSITOR] setOutput failed: $e")
            }
        }
    }

    /**
     * Attach an in-app phone preview surface (from [dev.zanderp.opencfmoto.HudViewActivity]'s
     * SurfaceView). The decoded AA frame is drawn aspect-fit into it. Forces one immediate redraw so
     * the preview shows the current (possibly static) frame right away instead of waiting for motion.
     */
    fun setPreview(surface: Surface, w: Int, h: Int) {
        handler.post {
            try {
                if (previewSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
                    EGL14.eglDestroySurface(eglDisplay, previewSurface)
                    previewSurface = EGL14.EGL_NO_SURFACE
                }
                previewSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0,
                )
                previewW = w; previewH = h
                computePreviewRect()
                log("[COMPOSITOR] preview attached ${w}x$h → draw rect=${ppW}x$ppH @($ppX,$ppY)")
                if (hasContent) drawFrame()
            } catch (e: Exception) {
                log("[COMPOSITOR] setPreview failed: $e")
            }
        }
    }

    /** The preview SurfaceView changed size (rotation / layout). Recompute the fit rect. */
    fun updatePreviewSize(w: Int, h: Int) {
        handler.post {
            previewW = w; previewH = h
            computePreviewRect()
            if (hasContent && previewSurface != EGL14.EGL_NO_SURFACE) drawFrame()
        }
    }

    /** Detach the phone preview. Blocks until the GL thread has destroyed the EGL surface, so the
     *  caller (surfaceDestroyed) can safely let Android release the underlying Surface afterwards. */
    fun clearPreview() {
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try {
                if (previewSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
                    EGL14.eglDestroySurface(eglDisplay, previewSurface)
                }
            } catch (_: Exception) {
            } finally {
                previewSurface = EGL14.EGL_NO_SURFACE
                previewW = 0; previewH = 0
                latch.countDown()
            }
        }
        try { latch.await(1, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    /** Aspect-fit the AA source ([bufW]x[bufH]) into the preview surface ([previewW]x[previewH]). */
    private fun computePreviewRect() {
        if (previewW == 0 || previewH == 0 || bufW == 0 || bufH == 0) return
        val srcAspect = bufW.toFloat() / bufH
        val viewAspect = previewW.toFloat() / previewH
        if (srcAspect < viewAspect) {           // source narrower → fit height, bars left/right
            ppH = previewH; ppW = Math.round(previewH * srcAspect)
        } else {                                // source wider → fit width, bars top/bottom
            ppW = previewW; ppH = Math.round(previewW / srcAspect)
        }
        ppX = (previewW - ppW) / 2
        ppY = (previewH - ppH) / 2
    }

    /**
     * Inverse of the letterbox: map a point in the bike canvas (the surface whose size we reported to
     * the bike, [canvasW]x[canvasH]) to the Android Auto source video space ([srcW]x[srcH]). Returns
     * null if the point falls in a black bar (outside the drawn AA rect) so the caller can drop it.
     * Used to translate dashboard touch coordinates into AA input coordinates.
     */
    fun mapCanvasToSource(cx: Int, cy: Int): Pair<Int, Int>? {
        if (vpW == 0 || vpH == 0 || srcW == 0 || srcH == 0) return null
        val rx = cx - vpX
        val ry = cy - vpY
        if (rx < 0 || ry < 0 || rx >= vpW || ry >= vpH) return null
        val sx = (rx.toLong() * srcW / vpW).toInt().coerceIn(0, srcW - 1)
        val sy = (ry.toLong() * srcH / vpH).toInt().coerceIn(0, srcH - 1)
        return sx to sy
    }

    /**
     * Derive the draw rectangle from the current [fitMode]. All three modes centre the rect, so the
     * inverse map in [mapCanvasToSource] stays a single linear transform regardless of mode:
     *  - [ScreenFit.FIT]     shrink to fully contain src (letterbox; rect ≤ canvas, black bars).
     *  - [ScreenFit.FILL]    grow to fully cover the canvas (rect ≥ canvas; edges clipped by GL).
     *  - [ScreenFit.STRETCH] rect = canvas exactly (ignores aspect).
     */
    private fun computeViewport() {
        if (canvasW == 0 || canvasH == 0 || srcW == 0 || srcH == 0) return
        val srcAspect = srcW.toFloat() / srcH
        val canvasAspect = canvasW.toFloat() / canvasH
        when (fitMode) {
            ScreenFit.STRETCH -> {
                vpW = canvasW; vpH = canvasH
            }
            ScreenFit.FIT -> {
                if (srcAspect < canvasAspect) {           // src narrower → fit height, bars left/right
                    vpH = canvasH; vpW = Math.round(canvasH * srcAspect)
                } else {                                  // src wider → fit width, bars top/bottom
                    vpW = canvasW; vpH = Math.round(canvasW / srcAspect)
                }
            }
            ScreenFit.FILL -> {
                if (srcAspect < canvasAspect) {           // src narrower → fit width, crop top/bottom
                    vpW = canvasW; vpH = Math.round(canvasW / srcAspect)
                } else {                                  // src wider → fit height, crop left/right
                    vpH = canvasH; vpW = Math.round(canvasH * srcAspect)
                }
            }
        }
        vpX = (canvasW - vpW) / 2
        vpY = (canvasH - vpH) / 2
        computeHudBars()
    }

    /** Recompute the letterbox bars the HUD paints into, and (re)allocate a GL texture per bar.
     *  Runs on the GL thread (called from [computeViewport], which is inside a handler post). */
    private fun computeHudBars() {
        val bars = HudBars.detect(canvasW, canvasH, vpX, vpY, vpW, vpH, fitMode, HUD_MIN_BAR_PX)
        // Free any textures we no longer need, allocate any new ones.
        if (bars.size != hudBars.size) {
            hudTextures.values.forEach { id -> try { GLES20.glDeleteTextures(1, intArrayOf(id), 0) } catch (_: Exception) {} }
            hudTextures.clear(); hudSigs.clear()
            for (i in bars.indices) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                hudTextures[i] = ids[0]
            }
        }
        hudBars = bars
        if (bars.isNotEmpty()) log("[COMPOSITOR] HUD bars=${bars.size} (${bars.joinToString { "${it.w}x${it.h}@(${it.x},${it.y})" }})")
    }

    /** Set the live-frame cap (frames/sec) from the user's [dev.zanderp.opencfmoto.PowerMode]. */
    fun setFrameCap(fps: Int) {
        val clamped = fps.coerceIn(10, 60)
        minFrameIntervalMs = (1000L / clamped)
        log("[COMPOSITOR] frame cap → ${clamped}fps (min ${minFrameIntervalMs}ms/frame)")
    }

    private fun onFrame() {
        try {
            surfaceTexture.updateTexImage()
        } catch (e: Exception) {
            return
        }
        hasContent = true
        // Throttle live frames to the power-mode cap. A too-soon frame is kept (texture already
        // latched above) and drawn by the keep-alive tick, so we cap the rate without losing motion.
        val idleMs = android.os.SystemClock.uptimeMillis() - lastDrawMs
        if (idleMs >= minFrameIntervalMs) drawFrame() else pendingFrame = true
    }

    /**
     * Re-emit the last decoded frame to the encoder if the Android Auto decoder has gone quiet, so the
     * bike keeps receiving video and never hits its media-socket timeout (CLIENT_INFO
     * socketTimeoutPeriodWifi, ~9s). AA legitimately pauses video during UI transitions (opening the
     * app launcher, an incoming call) and while the decoder restarts/recovers — without this the
     * encoder starves, the bike disconnects, and the whole projection drops (looks like a crash).
     * The dash instead shows a frozen last frame until live video resumes. Runs on the GL thread.
     */
    private val keepAlive = object : Runnable {
        override fun run() {
            if (hasContent && (windowSurface != EGL14.EGL_NO_SURFACE || previewSurface != EGL14.EGL_NO_SURFACE)) {
                val idleMs = android.os.SystemClock.uptimeMillis() - lastDrawMs
                if (pendingFrame && idleMs >= minFrameIntervalMs) {
                    // Flush the last coalesced live frame so motion doesn't stall at the cap.
                    drawFrame()
                } else if (idleMs >= IDLE_REDRAW_MS) {
                    // Static screen: a rare re-emit purely to hold the bike's media socket open.
                    drawFrame()
                }
            }
            handler.postDelayed(this, KEEPALIVE_TICK_MS)
        }
    }

    /**
     * Draw the current SurfaceTexture content (last decoded frame) into whichever targets exist: the
     * bike encoder ([windowSurface]) and/or the in-app phone preview ([previewSurface]). Each is
     * letterboxed into its own viewport.
     */
    private fun drawFrame() {
        if (windowSurface == EGL14.EGL_NO_SURFACE && previewSurface == EGL14.EGL_NO_SURFACE) return
        surfaceTexture.getTransformMatrix(texMatrix)
        // The HUD is painted into the bars of the BIKE surface only (not the phone preview).
        if (windowSurface != EGL14.EGL_NO_SURFACE) renderTo(windowSurface, vpX, vpY, vpW, vpH, drawHud = true)
        if (previewSurface != EGL14.EGL_NO_SURFACE) renderTo(previewSurface, ppX, ppY, ppW, ppH, drawHud = false)
        lastDrawMs = android.os.SystemClock.uptimeMillis()
        pendingFrame = false
    }

    /** Render the latched frame into [target], letterboxed to viewport ([vx],[vy],[vw],[vh]). */
    private fun renderTo(target: EGLSurface, vx: Int, vy: Int, vw: Int, vh: Int, drawHud: Boolean = false) {
        EGL14.eglMakeCurrent(eglDisplay, target, target, eglContext)

        // Black background (the letterbox bars).
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glViewport(vx, vy, vw, vh)
        GLES20.glUseProgram(program)

        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)

        // Paint the rider HUD into the black bars, over the AA frame (bike surface only). Guarded so a
        // HUD glitch can never take down the video path.
        if (drawHud && HudBus.enabled && hudBars.isNotEmpty()) {
            try { drawHudPanels() } catch (e: Exception) { log("[COMPOSITOR] HUD draw failed: $e") }
        }

        // Monotonic presentation time so repeated (keep-alive) frames aren't dropped as duplicate PTS.
        EGLExt.eglPresentationTimeANDROID(eglDisplay, target, System.nanoTime())
        EGL14.eglSwapBuffers(eglDisplay, target)
    }

    /**
     * Draw each HUD bar's texture into its bar rect on the current surface. The bitmap is only
     * re-rendered + re-uploaded when its content signature changes (~1 Hz), so a static screen stays
     * cheap; the textured-quad draw itself runs every frame. Assumes the AA program's attrib arrays
     * were already disabled by [renderTo]. Bar rects are top-left; GL viewport is bottom-left, hence
     * the Y flip.
     */
    private fun drawHudPanels() {
        val bars = hudBars
        if (bars.isEmpty()) return
        val cellsPerBar = HudLayout.distribute(
            HudLayout.cells(HudBus.stats, HudBus.unit, HudBus.clock, HudBus.elements), bars.size,
        )
        GLES20.glUseProgram(hudProgram)
        hudQuad.position(0)
        GLES20.glVertexAttribPointer(hudAPos, 2, GLES20.GL_FLOAT, false, 16, hudQuad)
        GLES20.glEnableVertexAttribArray(hudAPos)
        hudQuad.position(2)
        GLES20.glVertexAttribPointer(hudATex, 2, GLES20.GL_FLOAT, false, 16, hudQuad)
        GLES20.glEnableVertexAttribArray(hudATex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        for ((i, bar) in bars.withIndex()) {
            val tex = hudTextures[i] ?: continue
            val cells = cellsPerBar.getOrElse(i) { emptyList() }
            val sig = HudLayout.signature(cells)
            if (hudSigs[i] != sig) {
                val bmp = HudRenderer.render(bar, cells)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                bmp.recycle()
                hudSigs[i] = sig
            }
            GLES20.glViewport(bar.x, canvasH - (bar.y + bar.h), bar.w, bar.h)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glUniform1i(hudUTex, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisableVertexAttribArray(hudAPos)
        GLES20.glDisableVertexAttribArray(hudATex)
    }

    fun release() {
        handler.removeCallbacks(keepAlive)
        handler.post {
            // The EGL context teardown below frees all GL objects (incl. HUD textures/program); just
            // drop our bookkeeping so a reused instance starts clean.
            hudTextures.clear(); hudSigs.clear(); hudBars = emptyList()
            try { inputSurface?.release() } catch (_: Exception) {}
            try { if (::surfaceTexture.isInitialized) surfaceTexture.release() } catch (_: Exception) {}
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (windowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, windowSurface)
                if (previewSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, previewSurface)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        thread.quitSafely()
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val cfgAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, configs, 0, 1, numConfig, 0)
        eglConfig = configs[0]
        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
        // 1x1 pbuffer so the context can be current before we have an output window surface.
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
    }

    private fun initGl() {
        val vs = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
        """.trimIndent()
        program = linkProgram(vs, fs)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        Matrix.setIdentityM(texMatrix, 0)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        initHudGl()
    }

    /** Build the plain-2D program used to draw the HUD bitmap into the letterbox bars. */
    private fun initHudGl() {
        val vs = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() { gl_Position = aPosition; vTexCoord = aTexCoord; }
        """.trimIndent()
        val fs = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vTexCoord); }
        """.trimIndent()
        hudProgram = linkProgram(vs, fs)
        hudAPos = GLES20.glGetAttribLocation(hudProgram, "aPosition")
        hudATex = GLES20.glGetAttribLocation(hudProgram, "aTexCoord")
        hudUTex = GLES20.glGetUniformLocation(hudProgram, "uTex")
    }

    private fun linkProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val err = GLES20.glGetProgramInfoLog(p)
            throw RuntimeException("program link failed: $err")
        }
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val err = GLES20.glGetShaderInfoLog(s)
            throw RuntimeException("shader compile failed: $err")
        }
        return s
    }

    companion object {
        /** Minimum bar thickness (px) to bother painting the HUD — below this, text can't be legible. */
        private const val HUD_MIN_BAR_PX = 56
    }
}
