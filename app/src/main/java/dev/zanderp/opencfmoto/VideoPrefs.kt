package dev.zanderp.opencfmoto

import android.content.Context

/**
 * User-selectable video quality. Applied as a multiplier on top of each [BikeProfile]'s tuned
 * bitrate (see [BikeProfile.videoBitrate]), so per-dash tuning and the user's preference compose:
 * a rider on a weak link can pick [SMOOTH] to cut data/latency, or [SHARP] for a crisper map.
 *
 * Only the bitrate is scaled — resolution, H.264 profile and frame rate are left to the bike
 * profile, since those are the values documented as fragile end-to-end (black-screen risk).
 */
enum class VideoQuality(val multiplier: Float, val label: String) {
    SMOOTH(0.7f, "Smoother — less data/latency"),
    BALANCED(1.0f, "Balanced (recommended)"),
    SHARP(1.6f, "Sharper — more data"),
}

/**
 * How the Android Auto video (a fixed 16:9-ish landscape resolution) is placed into the bike's
 * differently-shaped mirror canvas by [AaCompositor]. AA can't render at the dash's aspect ratio, so
 * the rider picks the trade-off:
 *  - [FILL]    zoom to cover the whole screen; the AA image's edges are cropped (no black bars).
 *  - [FIT]     letterbox: whole AA image visible, black bars fill the leftover space.
 *  - [STRETCH] scale to the exact canvas; fills completely but distorts the aspect ratio.
 */
enum class ScreenFit(val label: String) {
    FILL("Fill — edge to edge (crops a little)"),
    FIT("Fit — no cropping (black bars)"),
    STRETCH("Stretch — fills, slight distortion"),
}

/**
 * Caps how many frames per second the [AaCompositor] pushes into the H.264 encoder. Every capped
 * frame is one fewer GPU composite + hardware encode + Wi-Fi transmit, so lowering it directly cuts
 * heat and battery drain. Below the cap the map still looks fluid; the trade is a touch less
 * smoothness on fast pans. Independent of the idle keep-alive (which drops far lower still).
 *
 * [AUTO] hands frame rate AND bitrate to [AdaptiveVideoController]: it starts smooth and steps both
 * down as the phone heats up (thermal status) or the bike Wi-Fi link congests (dropped frames),
 * then recovers as conditions ease — so the dash stays connected and smooth-at-a-lower-rate instead
 * of stuttering or dropping. [fps] here is the *starting* cap the controller adapts from. The fixed
 * modes disable the controller entirely and pin the rate, exactly as before.
 */
enum class PowerMode(val fps: Int, val label: String) {
    AUTO(30, "Auto — adapts to heat & signal"),
    SMOOTH(30, "Smooth — 30 fps (most battery)"),
    BALANCED(24, "Balanced — 24 fps (recommended)"),
    SAVER(20, "Battery saver — 20 fps (coolest)"),
}

/**
 * Android Auto video resolution + orientation.
 *
 * [AUTO] uses the resolution/orientation the bike profile proved works — correct for recognized
 * CFMoto dashes (matched from the QR/CLIENT_INFO). But an unrecognized dash falls back to the legacy
 * landscape 800×480, which is wrong for a tall/portrait screen. The explicit options let the rider
 * force the shape + size for any bike (e.g. a portrait display we don't yet have a profile for). AA
 * only supports these fixed sizes, so we can't match a panel's exact aspect — the compositor
 * letterboxes/crops per [ScreenFit]. HD sizes are crisper but heavier and can black-screen on some
 * embedded decoders — drop back to a smaller size or AUTO if a bike rejects them.
 */
enum class ResolutionMode(val label: String, val spec: AaVideoSpec?) {
    AUTO("Auto — match your bike (recommended)", null),
    LANDSCAPE_SD("Landscape · 800×480", AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)),
    LANDSCAPE_HD("Landscape · 1280×720 (HD)", AaVideoSpec(AaResolution.LANDSCAPE_1280x720, dpi = 160)),
    PORTRAIT_SD("Portrait · 720×1280", AaVideoSpec(AaResolution.PORTRAIT_720x1280, dpi = 240)),
    PORTRAIT_HD("Portrait · 1080×1920 (HD)", AaVideoSpec(AaResolution.PORTRAIT_1080x1920, dpi = 240)),
}

/**
 * Video/projection preferences. Each setting is **per bike** (scoped via [BikeScope] to the selected
 * bike in the garage): a portrait 1000 MT-X can keep Fit + portrait HD while a landscape 800MT keeps
 * Fill + SD, and switching the active bike switches its settings. When no bike is selected — or a bike
 * has never been customized — the previous single, global value is used as the default.
 */
object VideoPrefs {
    private const val PREFS = "opencfmoto_bike"
    private const val KEY_QUALITY = "video_quality"
    private const val KEY_FIT = "screen_fit"
    private const val KEY_POWER = "power_mode"
    private const val KEY_RESOLUTION = "resolution_mode"
    private const val KEY_HUD = "dash_hud"
    private const val KEY_SPEED_UNIT = "speed_unit"
    private const val KEY_HUD_ELEMENTS = "dash_hud_elements"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context): VideoQuality {
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_QUALITY, VideoQuality.BALANCED.name)
        return runCatching { VideoQuality.valueOf(name!!) }.getOrDefault(VideoQuality.BALANCED)
    }

    fun set(ctx: Context, quality: VideoQuality) {
        BikeScope.putString(prefs(ctx), ctx, KEY_QUALITY, quality.name)
    }

    /** Effective bitrate for [profile] under the current quality preference. */
    fun bitrateFor(ctx: Context, profile: BikeProfile): Int =
        (profile.videoBitrate * get(ctx).multiplier).toInt()

    fun fit(ctx: Context): ScreenFit {
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_FIT, ScreenFit.FILL.name)
        return runCatching { ScreenFit.valueOf(name!!) }.getOrDefault(ScreenFit.FILL)
    }

    fun setFit(ctx: Context, fit: ScreenFit) {
        BikeScope.putString(prefs(ctx), ctx, KEY_FIT, fit.name)
    }

    fun power(ctx: Context): PowerMode {
        // Default stays BALANCED until AUTO has more on-bike soak; riders can opt into Auto in Setup.
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_POWER, PowerMode.BALANCED.name)
        return runCatching { PowerMode.valueOf(name!!) }.getOrDefault(PowerMode.BALANCED)
    }

    fun setPower(ctx: Context, mode: PowerMode) {
        BikeScope.putString(prefs(ctx), ctx, KEY_POWER, mode.name)
    }

    fun resolution(ctx: Context): ResolutionMode {
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_RESOLUTION, ResolutionMode.AUTO.name)
        return runCatching { ResolutionMode.valueOf(name!!) }.getOrDefault(ResolutionMode.AUTO)
    }

    fun setResolution(ctx: Context, mode: ResolutionMode) {
        BikeScope.putString(prefs(ctx), ctx, KEY_RESOLUTION, mode.name)
    }

    /**
     * Dash HUD (idea B1): paint live speed/trip/phone telemetry into the FIT letterbox bars. On by
     * default, but only ever visible when [ScreenFit.FIT] leaves bars to paint into (FILL/STRETCH
     * cover the whole canvas).
     */
    fun hudEnabled(ctx: Context): Boolean =
        BikeScope.getString(prefs(ctx), ctx, KEY_HUD, "true") == "true"

    fun setHudEnabled(ctx: Context, on: Boolean) {
        BikeScope.putString(prefs(ctx), ctx, KEY_HUD, on.toString())
    }

    /** Speed/distance unit for the HUD. Defaults to mph in the few mph countries, else km/h. */
    fun speedUnit(ctx: Context): SpeedUnit {
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_SPEED_UNIT, defaultSpeedUnit().name)
        return runCatching { SpeedUnit.valueOf(name!!) }.getOrDefault(defaultSpeedUnit())
    }

    fun setSpeedUnit(ctx: Context, unit: SpeedUnit) {
        BikeScope.putString(prefs(ctx), ctx, KEY_SPEED_UNIT, unit.name)
    }

    private fun defaultSpeedUnit(): SpeedUnit {
        val mph = setOf("US", "GB", "MM", "LR")   // imperial-ish road-speed countries
        return if (java.util.Locale.getDefault().country.uppercase() in mph) SpeedUnit.MPH else SpeedUnit.KMH
    }

    /** Which HUD elements the rider has enabled (per bike). Unset = all on; an explicitly emptied set
     *  stays empty (the rider turned everything off — the master toggle is separate). */
    fun hudElements(ctx: Context): Set<HudElement> {
        val def = HudElement.ALL.joinToString(",") { it.name }
        val csv = BikeScope.getString(prefs(ctx), ctx, KEY_HUD_ELEMENTS, def) ?: def
        return csv.split(",").mapNotNull { runCatching { HudElement.valueOf(it.trim()) }.getOrNull() }.toSet()
    }

    fun setHudElement(ctx: Context, element: HudElement, on: Boolean) {
        val cur = hudElements(ctx).toMutableSet()
        if (on) cur.add(element) else cur.remove(element)
        BikeScope.putString(prefs(ctx), ctx, KEY_HUD_ELEMENTS, cur.joinToString(",") { it.name })
    }

    /**
     * The Android Auto video override for [profile] under the current [ResolutionMode], or null to
     * use the profile's own proven resolution ([ResolutionMode.AUTO], or when the explicit choice
     * already equals the profile's spec).
     */
    fun resolutionOverride(ctx: Context, profile: BikeProfile): AaVideoSpec? {
        val spec = resolution(ctx).spec ?: return null
        val base = profile.aaVideo
        if (spec.width == base.width && spec.height == base.height && spec.dpi == base.dpi) return null
        return spec
    }
}
