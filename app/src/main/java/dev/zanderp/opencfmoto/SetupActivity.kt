// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * One-time guided setup + persistent settings. Shows a live checklist of the prerequisites for
 * Android Auto projection (install, permissions, head-unit mode) and the display/battery options.
 * Each selector reflects the saved choice with a highlighted segment so the current setting is
 * always visible. Status is re-evaluated in [onResume] so returning from a permission prompt or the
 * Play Store re-ticks the steps.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var step1Title: TextView
    private lateinit var step1Btn: MaterialButton
    private lateinit var step2Title: TextView
    private lateinit var step2Btn: MaterialButton
    private lateinit var qualityDesc: TextView
    private lateinit var fitDesc: TextView
    private lateinit var powerDesc: TextView
    private lateinit var resDesc: TextView
    private lateinit var themeDesc: TextView
    private lateinit var dblTapDesc: TextView
    private lateinit var holdsDesc: TextView
    private lateinit var holdDesc: TextView
    private lateinit var nonTouchDesc: TextView
    private lateinit var forceTouchDesc: TextView
    private lateinit var profileDesc: TextView
    private lateinit var btStatus: TextView
    private lateinit var resumeBtn: MaterialButton

    private val importSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        importSettingsFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        // Draw content clear of the status/navigation bars (edge-to-edge is enforced on newer Android).
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.setup_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, b.top, v.paddingRight, b.bottom)
            insets
        }

        step1Title = findViewById(R.id.step1_title)
        step1Btn = findViewById(R.id.step1_btn)
        step2Title = findViewById(R.id.step2_title)
        step2Btn = findViewById(R.id.step2_btn)
        qualityDesc = findViewById(R.id.quality_desc)
        fitDesc = findViewById(R.id.fit_desc)
        powerDesc = findViewById(R.id.power_desc)
        resDesc = findViewById(R.id.res_desc)
        themeDesc = findViewById(R.id.theme_desc)
        dblTapDesc = findViewById(R.id.dbltap_desc)
        holdsDesc = findViewById(R.id.holds_desc)
        holdDesc = findViewById(R.id.hold_desc)
        nonTouchDesc = findViewById(R.id.nontouch_desc)
        forceTouchDesc = findViewById(R.id.forcetouch_desc)
        profileDesc = findViewById(R.id.profile_desc)
        btStatus = findViewById(R.id.bt_status)
        resumeBtn = findViewById(R.id.resume_perm_btn)

        step1Btn.setOnClickListener { openAndroidAuto() }
        step2Btn.setOnClickListener { requestMissingPermissions() }
        findViewById<MaterialButton>(R.id.step2_settings_btn).setOnClickListener { openAppSettings() }
        findViewById<MaterialButton>(R.id.step3_btn).setOnClickListener { openAndroidAutoSettings() }
        findViewById<MaterialButton>(R.id.language_system).setOnClickListener { setLanguage("") }
        findViewById<MaterialButton>(R.id.language_es).setOnClickListener { setLanguage("es") }
        findViewById<MaterialButton>(R.id.language_en).setOnClickListener { setLanguage("en") }

        findViewById<MaterialButton>(R.id.quality_smooth).setOnClickListener { setQuality(VideoQuality.SMOOTH) }
        findViewById<MaterialButton>(R.id.quality_balanced).setOnClickListener { setQuality(VideoQuality.BALANCED) }
        findViewById<MaterialButton>(R.id.quality_sharp).setOnClickListener { setQuality(VideoQuality.SHARP) }
        findViewById<MaterialButton>(R.id.fit_fill).setOnClickListener { setFit(ScreenFit.FILL) }
        findViewById<MaterialButton>(R.id.fit_fit).setOnClickListener { setFit(ScreenFit.FIT) }
        findViewById<MaterialButton>(R.id.fit_stretch).setOnClickListener { setFit(ScreenFit.STRETCH) }
        findViewById<MaterialButton>(R.id.power_auto).setOnClickListener { setPower(PowerMode.AUTO) }
        findViewById<MaterialButton>(R.id.power_smooth).setOnClickListener { setPower(PowerMode.SMOOTH) }
        findViewById<MaterialButton>(R.id.power_balanced).setOnClickListener { setPower(PowerMode.BALANCED) }
        findViewById<MaterialButton>(R.id.power_saver).setOnClickListener { setPower(PowerMode.SAVER) }
        findViewById<MaterialButton>(R.id.res_auto).setOnClickListener { setResolution(ResolutionMode.AUTO) }
        findViewById<MaterialButton>(R.id.res_land_sd).setOnClickListener { setResolution(ResolutionMode.LANDSCAPE_SD) }
        findViewById<MaterialButton>(R.id.res_land_hd).setOnClickListener { setResolution(ResolutionMode.LANDSCAPE_HD) }
        findViewById<MaterialButton>(R.id.res_port_sd).setOnClickListener { setResolution(ResolutionMode.PORTRAIT_SD) }
        findViewById<MaterialButton>(R.id.res_port_hd).setOnClickListener { setResolution(ResolutionMode.PORTRAIT_HD) }
        findViewById<MaterialButton>(R.id.theme_auto).setOnClickListener { setMapTheme(MapTheme.AUTO) }
        findViewById<MaterialButton>(R.id.theme_day).setOnClickListener { setMapTheme(MapTheme.DAY) }
        findViewById<MaterialButton>(R.id.theme_night).setOnClickListener { setMapTheme(MapTheme.NIGHT) }
        findViewById<MaterialButton>(R.id.dbltap_fast).setOnClickListener { setDoubleTap(DoubleTapDelay.FAST) }
        findViewById<MaterialButton>(R.id.dbltap_normal).setOnClickListener { setDoubleTap(DoubleTapDelay.NORMAL) }
        findViewById<MaterialButton>(R.id.dbltap_slow).setOnClickListener { setDoubleTap(DoubleTapDelay.SLOW) }
        findViewById<MaterialButton>(R.id.dbltap_very_slow).setOnClickListener {
            setDoubleTap(DoubleTapDelay.VERY_SLOW)
        }
        findViewById<MaterialButton>(R.id.holds_on).setOnClickListener { setHoldsEnabled(true) }
        findViewById<MaterialButton>(R.id.holds_off).setOnClickListener { setHoldsEnabled(false) }
        findViewById<MaterialButton>(R.id.hold_short).setOnClickListener { setLongPress(LongPressDelay.SHORT) }
        findViewById<MaterialButton>(R.id.hold_normal).setOnClickListener { setLongPress(LongPressDelay.NORMAL) }
        findViewById<MaterialButton>(R.id.hold_long).setOnClickListener { setLongPress(LongPressDelay.LONG) }
        findViewById<MaterialButton>(R.id.autoconnect_on).setOnClickListener { setAutoConnect(true) }
        findViewById<MaterialButton>(R.id.autoconnect_off).setOnClickListener { setAutoConnect(false) }
        findViewById<MaterialButton>(R.id.recovery_on).setOnClickListener { setAutoRecovery(true) }
        findViewById<MaterialButton>(R.id.recovery_off).setOnClickListener { setAutoRecovery(false) }
        findViewById<MaterialButton>(R.id.logtrips_on).setOnClickListener { setLogTrips(true) }
        findViewById<MaterialButton>(R.id.logtrips_off).setOnClickListener { setLogTrips(false) }
        findViewById<MaterialButton>(R.id.nontouch_on).setOnClickListener { setForceNonTouch(true) }
        findViewById<MaterialButton>(R.id.nontouch_off).setOnClickListener { setForceNonTouch(false) }
        findViewById<MaterialButton>(R.id.forcetouch_on).setOnClickListener { setForceTouch(true) }
        findViewById<MaterialButton>(R.id.forcetouch_off).setOnClickListener { setForceTouch(false) }
        findViewById<MaterialButton>(R.id.profile_auto).setOnClickListener { setProfileOverride(ProfileOverride.AUTO) }
        findViewById<MaterialButton>(R.id.profile_legacy).setOnClickListener { setProfileOverride(ProfileOverride.LEGACY) }
        findViewById<MaterialButton>(R.id.profile_nk800).setOnClickListener { setProfileOverride(ProfileOverride.NK800) }
        findViewById<MaterialButton>(R.id.profile_800mt).setOnClickListener { setProfileOverride(ProfileOverride.CFDL26_LAND) }
        findViewById<MaterialButton>(R.id.profile_1000mtx).setOnClickListener { setProfileOverride(ProfileOverride.CFDL26_PORT) }
        findViewById<MaterialButton>(R.id.profile_nk_adv).setOnClickListener { setProfileOverride(ProfileOverride.NK_ADV) }
        findViewById<MaterialButton>(R.id.profile_clc450).setOnClickListener { setProfileOverride(ProfileOverride.CLC450) }
        findViewById<MaterialButton>(R.id.btn_screen_margins).setOnClickListener { ScreenMarginsActivity.start(this) }
        findViewById<MaterialButton>(R.id.btn_custom_resolution).setOnClickListener { CustomResolutionActivity.start(this) }
        findViewById<MaterialButton>(R.id.transport_auto).setOnClickListener { setTransport(WifiTransport.AUTO) }
        findViewById<MaterialButton>(R.id.transport_ap).setOnClickListener { setTransport(WifiTransport.AP) }
        findViewById<MaterialButton>(R.id.transport_p2p).setOnClickListener { setTransport(WifiTransport.P2P) }
        findViewById<MaterialButton>(R.id.secrets_on).setOnClickListener { setSecrets(true) }
        findViewById<MaterialButton>(R.id.secrets_off).setOnClickListener { setSecrets(false) }
        findViewById<MaterialButton>(R.id.telemetry_on).setOnClickListener { setTelemetry(true) }
        findViewById<MaterialButton>(R.id.telemetry_off).setOnClickListener { setTelemetry(false) }
        findViewById<MaterialButton>(R.id.settings_share).setOnClickListener { shareSettingsJson() }
        findViewById<MaterialButton>(R.id.settings_import).setOnClickListener {
            importSettingsLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }
        findViewById<MaterialButton>(R.id.bt_settings_btn).setOnClickListener { BluetoothHelper.openBluetoothSettings(this) }
        resumeBtn.setOnClickListener { requestOverlayPermission() }

        findViewById<MaterialButton>(R.id.setup_done_btn).setOnClickListener {
            markSeen(this)
            finish()
        }
    }

    /** Write the portable settings JSON and open the system share sheet (Discord, Drive, …). */
    private fun shareSettingsJson() {
        try {
            val json = SettingsBackup.exportJson(this)
            val dir = File(cacheDir, "settings").apply { mkdirs() }
            val file = File(dir, SettingsBackup.suggestedFileName(this))
            file.writeText(json, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val profile = ProfilePrefs.get(this).shortLabel
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, "OpenCfMoto bike tuning — $profile")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "OpenCfMoto bike tuning ($profile) — profile / resolution / margins / buttons " +
                        "(no passwords, no bike name/SSID).\n" +
                        "Import via Setup → Wi‑Fi & logs → Import…",
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
            }
            startActivity(Intent.createChooser(send, "Share settings JSON"))
        } catch (e: Exception) {
            Toast.makeText(this, uiText("Share failed: $e"), Toast.LENGTH_LONG).show()
        }
    }

    private fun importSettingsFromUri(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            if (text.isNullOrBlank()) {
                Toast.makeText(this, uiText("Empty file"), Toast.LENGTH_SHORT).show()
                return
            }
            val onto = BikeMemory.lastBikeName(this) ?: "the selected bike"
            AlertDialog.Builder(this)
                .setTitle(uiText("Import settings?"))
                .setMessage(
                    uiText("Replace bike tuning for $onto — profile, resolution, fit, power, margins, ") +
                        "handlebar buttons, Control AA, non-touch, Wi‑Fi transport?\n\n" +
                        "Personal prefs (map theme, saved places, auto-connect, …) are left alone. " +
                        "Wi‑Fi passwords are never imported."
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(uiText("Import")) { _, _ ->
                    val result = SettingsBackup.importJson(this, text)
                    refreshOptions()
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, uiText("Import failed: $e"), Toast.LENGTH_LONG).show()
        }
    }

    private fun setQuality(q: VideoQuality) {
        VideoPrefs.set(this, q)
        refreshOptions()
        toast("Video quality: ${uiText(q.label)}")
    }

    private fun setFit(f: ScreenFit) {
        VideoPrefs.setFit(this, f)
        refreshOptions()
        toast("Screen fit: ${uiText(f.label)}")
    }

    private fun setPower(m: PowerMode) {
        VideoPrefs.setPower(this, m)
        refreshOptions()
        toast("Power mode: ${uiText(m.label)}")
    }

    private fun setResolution(m: ResolutionMode) {
        VideoPrefs.setResolution(this, m)
        refreshOptions()
        toast("Resolution: ${uiText(m.label)}")
    }

    /** Map day/night applies live (no reconnect needed) — push it to any running AA session. */
    private fun setMapTheme(theme: MapTheme) {
        NightPrefs.setTheme(this, theme)
        AaVideoBridge.nightSink?.invoke(NightPrefs.isNightNow(this))
        refreshOptions()
        Toast.makeText(this, uiText("Map theme: ${uiText(theme.label)}"), Toast.LENGTH_SHORT).show()
    }

    /** Button timing applies live — the next press uses the new window. */
    private fun setDoubleTap(delay: DoubleTapDelay) {
        ButtonTimingPrefs.setDoubleTap(this, delay)
        refreshOptions()
        Toast.makeText(this, uiText("Double-tap delay: ${uiText(delay.label)}"), Toast.LENGTH_SHORT).show()
    }

    private fun setHoldsEnabled(on: Boolean) {
        ButtonTimingPrefs.setHoldsEnabled(this, on)
        refreshOptions()
        Toast.makeText(
            this,
            uiText(if (on) "Hold gestures enabled" else "Hold gestures disabled"),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun setLongPress(delay: LongPressDelay) {
        ButtonTimingPrefs.setLongPress(this, delay)
        refreshOptions()
        Toast.makeText(this, uiText("Hold delay: ${uiText(delay.label)}"), Toast.LENGTH_SHORT).show()
    }

    private fun setAutoConnect(on: Boolean) {
        AppSettings.setAutoConnect(this, on)
        refreshOptions()
        Toast.makeText(this, uiText("Auto-connect ${if (on) "on" else "off"}"), Toast.LENGTH_SHORT).show()
    }

    private fun setAutoRecovery(on: Boolean) {
        AppSettings.setAutoRecovery(this, on)
        refreshOptions()
        Toast.makeText(this, uiText("Auto-recovery ${if (on) "on" else "off"}"), Toast.LENGTH_SHORT).show()
    }

    private fun setLogTrips(on: Boolean) {
        AppSettings.setLogTrips(this, on)
        refreshOptions()
        Toast.makeText(this, uiText("Trip logging ${if (on) "on" else "off"}"), Toast.LENGTH_SHORT).show()
    }

    private fun setForceNonTouch(on: Boolean) {
        AppSettings.setForceNonTouch(this, on)
        refreshOptions()
        toast(uiText("Disable touchscreen: ${if (on) "on" else "off"}"))
    }

    private fun setForceTouch(on: Boolean) {
        AppSettings.setForceTouch(this, on)
        refreshOptions()
        toast(uiText("Force touchscreen: ${if (on) "on" else "off"}"))
    }

    private fun setProfileOverride(ov: ProfileOverride) {
        ProfilePrefs.set(this, ov)
        refreshOptions()
        toast("Bike profile: ${uiText(ov.shortLabel)}")
    }

    private fun setTransport(t: WifiTransport) {
        AppSettings.setTransport(this, t)
        refreshOptions()
        toast("Wi‑Fi transport: ${uiText(t.label)}")
    }

    private fun setSecrets(on: Boolean) {
        AppSettings.setIncludeSecretsInLogs(this, on)
        refreshOptions()
        Toast.makeText(
            this,
            uiText(
                if (on) "Shared logs will include secrets — turn off before posting publicly"
                else "Log redaction on",
            ),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun setTelemetry(on: Boolean) {
        AppSettings.setAnonymousTelemetry(this, on)
        refreshOptions()
        if (on) AnonymousTelemetry.onAppStart(this)
        Toast.makeText(
            this,
            if (on) getString(R.string.setup_anonymous_telemetry_on)
            else getString(R.string.setup_anonymous_telemetry_off),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, uiText("$msg (applies next connect)"), Toast.LENGTH_SHORT).show()

    /** Apply an app-specific locale; an empty tag follows the phone language. */
    private fun setLanguage(languageTag: String) {
        val current = AppCompatDelegate.getApplicationLocales().get(0)?.language.orEmpty()
        if (current == languageTag) return
        val locales = if (languageTag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageTag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** Update every selector's description + highlight the active segment. */
    private fun refreshOptions() {
        val quality = VideoPrefs.get(this)
        val fit = VideoPrefs.fit(this)
        val power = VideoPrefs.power(this)
        val res = VideoPrefs.resolution(this)
        val theme = NightPrefs.theme(this)
        val dbl = ButtonTimingPrefs.doubleTap(this)
        val holdsOn = ButtonTimingPrefs.holdsEnabled(this)
        val hold = ButtonTimingPrefs.longPress(this)
        val language = AppCompatDelegate.getApplicationLocales().get(0)?.language.orEmpty()
        highlight(language,
            R.id.language_system to "",
            R.id.language_es to "es",
            R.id.language_en to "en")
        qualityDesc.text = uiText(quality.label)
        fitDesc.text = uiText(fit.label)
        powerDesc.text = uiText(power.label)
        resDesc.text = uiText(res.label)
        themeDesc.text = uiText(theme.label)
        dblTapDesc.text = uiText(dbl.label)
        holdsDesc.text = getString(R.string.holds_desc)
        holdDesc.text = uiText(hold.label)
        nonTouchDesc.text = uiText(
            if (AppSettings.forceNonTouch(this))
                "On — focus/knob UI so handlebar buttons work"
            else
                "Off — use the bike profile (touch dashes stay touch)",
        )
        forceTouchDesc.text = uiText(
            if (AppSettings.forceTouch(this))
                "On — Android Auto touch UI (tap the dash)"
            else
                "Off — use the bike profile (1000 MT‑X stays focus/knob)",
        )
        val pov = ProfilePrefs.get(this)
        profileDesc.text = uiText("${pov.shortLabel} — ${pov.detail}")

        highlight(quality,
            R.id.quality_smooth to VideoQuality.SMOOTH,
            R.id.quality_balanced to VideoQuality.BALANCED,
            R.id.quality_sharp to VideoQuality.SHARP)
        highlight(fit,
            R.id.fit_fill to ScreenFit.FILL,
            R.id.fit_fit to ScreenFit.FIT,
            R.id.fit_stretch to ScreenFit.STRETCH)
        highlight(power,
            R.id.power_auto to PowerMode.AUTO,
            R.id.power_smooth to PowerMode.SMOOTH,
            R.id.power_balanced to PowerMode.BALANCED,
            R.id.power_saver to PowerMode.SAVER)
        highlight(res,
            R.id.res_auto to ResolutionMode.AUTO,
            R.id.res_land_sd to ResolutionMode.LANDSCAPE_SD,
            R.id.res_land_hd to ResolutionMode.LANDSCAPE_HD,
            R.id.res_port_sd to ResolutionMode.PORTRAIT_SD,
            R.id.res_port_hd to ResolutionMode.PORTRAIT_HD)
        highlight(theme,
            R.id.theme_auto to MapTheme.AUTO,
            R.id.theme_day to MapTheme.DAY,
            R.id.theme_night to MapTheme.NIGHT)
        highlight(dbl,
            R.id.dbltap_fast to DoubleTapDelay.FAST,
            R.id.dbltap_normal to DoubleTapDelay.NORMAL,
            R.id.dbltap_slow to DoubleTapDelay.SLOW,
            R.id.dbltap_very_slow to DoubleTapDelay.VERY_SLOW)
        highlight(holdsOn,
            R.id.holds_on to true,
            R.id.holds_off to false)
        highlight(hold,
            R.id.hold_short to LongPressDelay.SHORT,
            R.id.hold_normal to LongPressDelay.NORMAL,
            R.id.hold_long to LongPressDelay.LONG)

        highlight(AppSettings.autoConnect(this),
            R.id.autoconnect_on to true,
            R.id.autoconnect_off to false)
        highlight(AppSettings.autoRecovery(this),
            R.id.recovery_on to true,
            R.id.recovery_off to false)
        highlight(AppSettings.logTrips(this),
            R.id.logtrips_on to true,
            R.id.logtrips_off to false)
        highlight(AppSettings.forceNonTouch(this),
            R.id.nontouch_on to true,
            R.id.nontouch_off to false)
        highlight(AppSettings.forceTouch(this),
            R.id.forcetouch_on to true,
            R.id.forcetouch_off to false)
        highlight(ProfilePrefs.get(this),
            R.id.profile_auto to ProfileOverride.AUTO,
            R.id.profile_legacy to ProfileOverride.LEGACY,
            R.id.profile_nk800 to ProfileOverride.NK800,
            R.id.profile_800mt to ProfileOverride.CFDL26_LAND,
            R.id.profile_1000mtx to ProfileOverride.CFDL26_PORT,
            R.id.profile_nk_adv to ProfileOverride.NK_ADV,
            R.id.profile_clc450 to ProfileOverride.CLC450)
        val transport = AppSettings.transport(this)
        findViewById<android.widget.TextView>(R.id.transport_desc).text = uiText(transport.label)
        highlight(transport,
            R.id.transport_auto to WifiTransport.AUTO,
            R.id.transport_ap to WifiTransport.AP,
            R.id.transport_p2p to WifiTransport.P2P)
        val secrets = AppSettings.includeSecretsInLogs(this)
        findViewById<android.widget.TextView>(R.id.secrets_desc).text =
            uiText(
                if (secrets) "On — passwords/serials stay in shared logs"
                else "Off — passwords and serials are redacted (recommended)",
            )
        highlight(secrets, R.id.secrets_on to true, R.id.secrets_off to false)
        val telemetry = AppSettings.anonymousTelemetry(this)
        findViewById<android.widget.TextView>(R.id.telemetry_desc).text =
            getString(R.string.setup_anonymous_telemetry_desc)
        findViewById<android.widget.TextView>(R.id.telemetry_id).text =
            getString(R.string.setup_anonymous_id, AnonymousTelemetry.anonUuidForDisplay(this))
        highlight(telemetry, R.id.telemetry_on to true, R.id.telemetry_off to false)
    }

    /** Refresh the Bluetooth pairing status line shown in the helper card. */
    private fun refreshBluetooth() {
        btStatus.text = BluetoothHelper.status(this).describe()
    }

    /** Paint the segment matching [selected] in brand color; the rest stay neutral tonal. */
    private fun <T> highlight(selected: T, vararg pairs: Pair<Int, T>) {
        val onColor = ContextCompat.getColor(this, R.color.brand_orange)
        val onText = ContextCompat.getColor(this, R.color.on_brand)
        val offColor = ContextCompat.getColor(this, R.color.surface_high)
        val offText = ContextCompat.getColor(this, R.color.text_primary)
        for ((id, value) in pairs) {
            val btn = findViewById<MaterialButton>(id)
            val on = value == selected
            btn.backgroundTintList = ColorStateList.valueOf(if (on) onColor else offColor)
            btn.setTextColor(if (on) onText else offText)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshOptions()
        refreshBluetooth()
    }

    private fun refresh() {
        val aaOk = SetupHelper.isAndroidAutoInstalled(this)
        step1Title.text = tick(aaOk) + uiText(" 1. Android Auto")
        step1Btn.text = uiText(if (aaOk) "Open Android Auto" else "Install Android Auto")

        val permsOk = SetupHelper.permissionsGranted(this)
        step2Title.text = tick(permsOk) + uiText(" 2. Permissions")
        step2Btn.text = uiText(if (permsOk) "All granted" else "Grant permissions")
        step2Btn.isEnabled = !permsOk

        val resumeOk = SetupHelper.canAutoResume(this)
        resumeBtn.text = uiText(if (resumeOk) "\u2713 Seamless resume enabled" else "Enable seamless resume")
        resumeBtn.isEnabled = !resumeOk
    }

    /** Deep-link to the "Display over other apps" screen for this app (overlay = seamless auto-resume). */
    private fun requestOverlayPermission() {
        if (SetupHelper.canAutoResume(this)) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", packageName, null)))
            Toast.makeText(this, uiText("Turn on \u201cDisplay over other apps\u201d for seamless resume"),
                Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(this, uiText("Couldn't open the overlay permission screen"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun tick(ok: Boolean): String = if (ok) "\u2713" else "\u2022"

    private fun requestMissingPermissions() {
        val missing = SetupHelper.missingPermissions(this)
        if (missing.isEmpty()) { refresh(); return }
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        // If anything is still missing after the prompt, the user likely hit "Don't allow" — point
        // them at the system settings where it can still be granted.
        if (!SetupHelper.permissionsGranted(this)) {
            Toast.makeText(this,
                uiText("Some permissions are still off — use \u201cApp settings\u201d to enable them."),
                Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) {
            Toast.makeText(this, uiText("Couldn't open app settings"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAndroidAuto() {
        if (SetupHelper.isAndroidAutoInstalled(this)) {
            openAndroidAutoSettings()
            return
        }
        val market = Intent(Intent.ACTION_VIEW,
            Uri.parse("market://details?id=${SetupHelper.GEARHEAD_PACKAGE}"))
        try {
            startActivity(market)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=${SetupHelper.GEARHEAD_PACKAGE}")))
            } catch (_: Exception) {
                Toast.makeText(this, uiText("Couldn't open the Play Store"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openAndroidAutoSettings() {
        try {
            startActivity(Intent("android.settings.ANDROID_AUTO_SETTINGS")
                .setPackage(SetupHelper.GEARHEAD_PACKAGE))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_MAIN).setClassName(
                    SetupHelper.GEARHEAD_PACKAGE,
                    "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"))
            } catch (_: Exception) {
                Toast.makeText(this, uiText("Couldn't open Android Auto settings"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val REQ_PERMS = 42
        private const val PREFS = "opencfmoto_bike"
        private const val KEY_SEEN = "setup_seen"

        fun hasSeen(ctx: android.content.Context): Boolean =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SEEN, false)

        fun markSeen(ctx: android.content.Context) {
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
        }

        fun start(ctx: android.content.Context) {
            ctx.startActivity(Intent(ctx, SetupActivity::class.java))
        }
    }
}
