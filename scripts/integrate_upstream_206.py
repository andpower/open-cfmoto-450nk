#!/usr/bin/env python3
"""Finalize the OpenCfMoto 2.0.6 merge for the independent 450NK edition.

The workflow merges upstream with the recursive strategy preferring the 450NK side on conflicts.
This script then applies the small, explicit integration points that must not be lost:
- independent package/repository/version identity;
- no active upstream telemetry endpoint;
- Android 11 Bluetooth compatibility;
- parked Apps launcher registration;
- delayed external-app launch only after EasyConn is actually streaming;
- post-launch frame-health check and one automatic reconnect;
- Spanish translations for the new diagnostics.

Every important edit is validated. A changed upstream layout should fail the workflow instead of
silently producing an APK with a half-applied projection path.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def replace_exact(text: str, old: str, new: str, label: str, *, count: int = 1) -> str:
    found = text.count(old)
    if found != count:
        raise RuntimeError(f"{label}: expected {count} occurrence(s), found {found}")
    return text.replace(old, new, count)


def ensure_after(text: str, anchor: str, addition: str, label: str) -> str:
    if addition.strip() in text:
        return text
    if anchor not in text:
        raise RuntimeError(f"{label}: anchor not found")
    return text.replace(anchor, anchor + addition, 1)


def patch_build_gradle() -> None:
    rel = "app/build.gradle.kts"
    text = read(rel)
    text, n = re.subn(r'applicationId\s*=\s*"[^"]+"', 'applicationId = "com.andpower.opencfmoto450nk"', text, count=1)
    if n != 1:
        raise RuntimeError("build.gradle: applicationId not found")
    text, n = re.subn(r'versionCode\s*=\s*\d+', 'versionCode = 37', text, count=1)
    if n != 1:
        raise RuntimeError("build.gradle: versionCode not found")
    text, n = re.subn(r'versionName\s*=\s*"[^"]+"', 'versionName = "2.3.0-450nk"', text, count=1)
    if n != 1:
        raise RuntimeError("build.gradle: versionName not found")

    # Upstream 2.0.6 ships an optional telemetry client. The independent 450NK edition keeps the
    # endpoint empty, so no install/error upload can occur even if the upstream toggle is present.
    text = re.sub(
        r'(val telemetryUrl\s*=\s*\(project\.findProperty\("telemetryUrl"\) as String\?\)\s*\n\s*\?: System\.getenv\("TELEMETRY_URL"\)\s*\n\s*\?:)\s*"[^"]*"',
        r'\1 ""',
        text,
        count=1,
    )
    write(rel, text)


def patch_manifest() -> None:
    rel = "app/src/main/AndroidManifest.xml"
    text = read(rel)

    bluetooth = (
        '    <!-- Android 11 and older use the legacy Bluetooth permission model. -->\n'
        '    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />\n'
        '    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />\n'
    )
    if 'android.permission.BLUETOOTH" android:maxSdkVersion="30"' not in text:
        anchor = '    <uses-permission android:name="android.permission.CAMERA" />\n'
        if anchor not in text:
            raise RuntimeError("manifest: camera permission anchor missing")
        text = text.replace(anchor, anchor + bluetooth, 1)

    package_queries = [
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "org.videolan.vlc",
        "com.spotify.music",
        "com.plexapp.android",
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.google.android.apps.photos",
    ]
    queries_end = "    </queries>"
    for package in package_queries:
        line = f'        <package android:name="{package}" />'
        if line not in text:
            if queries_end not in text:
                raise RuntimeError("manifest: queries block missing")
            text = text.replace(queries_end, line + "\n" + queries_end, 1)

    apps_activity = (
        '        <activity\n'
        '            android:name=".AppsActivity"\n'
        '            android:exported="false" />\n'
    )
    if 'android:name=".AppsActivity"' not in text:
        controls = (
            '        <activity\n'
            '            android:name=".ControlsActivity"\n'
            '            android:exported="false" />\n'
        )
        if controls not in text:
            raise RuntimeError("manifest: ControlsActivity anchor missing")
        text = text.replace(controls, controls + apps_activity, 1)

    # Upstream may add a generated locale config. Keep it when the XML exists, but never duplicate it.
    locale_file = ROOT / "app/src/main/res/xml/locales_config.xml"
    if locale_file.exists() and 'android:localeConfig="@xml/locales_config"' not in text:
        icon_line = '        android:icon="@mipmap/ic_launcher"\n'
        if icon_line not in text:
            raise RuntimeError("manifest: application icon anchor missing")
        text = text.replace(icon_line, icon_line + '        android:localeConfig="@xml/locales_config"\n', 1)

    write(rel, text)


def patch_repo_identity() -> None:
    replacements = {
        "app/src/main/java/dev/zanderp/opencfmoto/AboutActivity.kt": [
            ("https://github.com/zanderp/open-cfmoto", "https://github.com/andpower/open-cfmoto-450nk"),
        ],
        "app/src/main/java/dev/zanderp/opencfmoto/AppHttp.kt": [
            ("https://github.com/zanderp/open-cfmoto", "https://github.com/andpower/open-cfmoto-450nk"),
        ],
        "app/src/main/java/dev/zanderp/opencfmoto/UpdateChecker.kt": [
            ("zanderp/open-cfmoto", "andpower/open-cfmoto-450nk"),
        ],
    }
    for rel, pairs in replacements.items():
        path = ROOT / rel
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        for old, new in pairs:
            text = text.replace(old, new)
        path.write_text(text, encoding="utf-8")


def patch_main_activity() -> None:
    rel = "app/src/main/java/dev/zanderp/opencfmoto/MainActivity.kt"
    text = read(rel)

    # Apps mode already exists in the 450NK branch. Do not silently proceed if an upstream merge
    # unexpectedly removed it.
    required = [
        "private var pendingAppPackage: String? = null",
        "private fun startAppMirrorLink()",
        "private fun launchPendingMirrorApp()",
        "private fun clearPendingApp()",
    ]
    for marker in required:
        if marker not in text:
            raise RuntimeError(f"MainActivity: missing 450NK marker: {marker}")

    if "private var appProjectionWaitToken = 0" not in text:
        field_anchor = "    private var pendingAppLabel: String? = null\n"
        fields = (
            "    /** Cancels stale delayed launch/health tasks when the rider changes mode. */\n"
            "    private var appProjectionWaitToken = 0\n"
            "    private val appProjectionHandler = android.os.Handler(android.os.Looper.getMainLooper())\n"
        )
        text = ensure_after(text, field_anchor, fields, "MainActivity projection fields")

    # Both saved-bike and freshly-scanned paths used to launch the external app immediately after
    # starting the asynchronous Wi-Fi join. Wait for the first frame actually delivered to the TFT.
    old_scan = (
        "            joinWifi(qr, gateOnAaSteady = false)\n"
        "            if (pendingAppPackage != null) {\n"
        "                launchPendingMirrorApp()\n"
        "            } else {\n"
    )
    new_scan = (
        "            joinWifi(qr, gateOnAaSteady = false)\n"
        "            if (pendingAppPackage != null) {\n"
        "                waitForAppProjectionStream()\n"
        "            } else {\n"
    )
    if old_scan in text:
        text = text.replace(old_scan, new_scan, 1)
    elif new_scan not in text:
        raise RuntimeError("MainActivity: scan Apps launch block not found")

    old_saved = (
        "            joinWifi(saved, gateOnAaSteady = false)\n"
        "            launchPendingMirrorApp()\n"
    )
    new_saved = (
        "            joinWifi(saved, gateOnAaSteady = false)\n"
        "            waitForAppProjectionStream()\n"
    )
    if old_saved in text:
        text = text.replace(old_saved, new_saved, 1)
    elif new_saved not in text:
        raise RuntimeError("MainActivity: saved-bike Apps launch block not found")

    if "private fun waitForAppProjectionStream()" not in text:
        method_anchor = "    private fun launchPendingMirrorApp() {\n"
        wait_method = r'''    /**
     * Do not open the selected app until the complete path is proven:
     * MediaProjection → encoder → EasyConn handshake → TFT data requests → first delivered frame.
     *
     * Launching immediately after [joinWifi] raced the asynchronous Wi‑Fi/PXC setup. The selected
     * app appeared on the phone while the dash still had no media channel, which looked like the app
     * "couldn't be transmitted". [EasyConnProber.isStreaming] is only true after frame #1 is sent.
     */
    private fun waitForAppProjectionStream() {
        val label = pendingAppLabel ?: return
        val token = ++appProjectionWaitToken
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var lastStageLogAt = 0L
        log("→ Apps: waiting for confirmed TFT video before opening $label")
        Toast.makeText(
            this,
            uiText("Preparing $label on the bike…"),
            Toast.LENGTH_LONG,
        ).show()

        val poll = object : Runnable {
            override fun run() {
                if (token != appProjectionWaitToken || pendingAppPackage == null) return
                val active = BikeLink.prober ?: prober
                val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                val recentFrame = active.isStreaming && active.msSinceLastFrame() <= 2_500L

                if (recentFrame) {
                    log("→ Apps: TFT stream confirmed after ${elapsed}ms; opening $label")
                    launchPendingMirrorApp()
                    return
                }

                if (elapsed >= APP_STREAM_START_TIMEOUT_MS) {
                    val stage = when {
                        !active.isRunning -> "EasyConn did not start"
                        active.msSinceLastFrame() == Long.MAX_VALUE -> "the TFT requested no video frame"
                        else -> "the video stream stalled"
                    }
                    log("→ Apps failed before launch: $stage (${elapsed}ms)")
                    Toast.makeText(
                        applicationContext,
                        uiText("Couldn't transmit $label: $stage. Open Logs and reconnect."),
                        Toast.LENGTH_LONG,
                    ).show()
                    ConnectionState.set(Phase.ERROR, "Apps: $stage")
                    stopEverything()
                    return
                }

                if (elapsed - lastStageLogAt >= 2_000L) {
                    lastStageLogAt = elapsed
                    val phase = ConnectionState.phase
                    log(
                        "→ Apps: preparing $label — phase=$phase prober=${active.isRunning} " +
                            "stream=${active.isStreaming} lastFrame=${active.msSinceLastFrame()}ms",
                    )
                }
                appProjectionHandler.postDelayed(this, APP_STREAM_POLL_MS)
            }
        }
        appProjectionHandler.post(poll)
    }

'''
        if method_anchor not in text:
            raise RuntimeError("MainActivity: launchPendingMirrorApp anchor missing")
        text = text.replace(method_anchor, wait_method + method_anchor, 1)

    # Verify the stream remains alive after the external task covers OpenCfMoto. One automatic
    # EasyConn reconnect is safe and materially better than leaving a frozen TFT with no feedback.
    old_launch_tail = (
        "            log(\"→ Apps: launched $label ($component); handlebar mode=$mediaMode\")\n"
        "            val drm = if (AppsCatalog.mayUseProtectedVideo(packageName)) {\n"
    )
    new_launch_tail = (
        "            log(\"→ Apps: launched $label ($component); handlebar mode=$mediaMode\")\n"
        "            verifyAppProjectionHealth(label)\n"
        "            val drm = if (AppsCatalog.mayUseProtectedVideo(packageName)) {\n"
    )
    if old_launch_tail in text:
        text = text.replace(old_launch_tail, new_launch_tail, 1)
    elif new_launch_tail not in text:
        raise RuntimeError("MainActivity: post-launch health insertion point missing")

    if "private fun verifyAppProjectionHealth(label: String)" not in text:
        clear_anchor = "    private fun clearPendingApp() {\n"
        health_method = r'''    /** Ensure whole-screen capture keeps feeding the TFT after the external app opens. */
    private fun verifyAppProjectionHealth(label: String) {
        val active = BikeLink.prober ?: prober
        appProjectionHandler.postDelayed({
            if (!active.isRunning) return@postDelayed
            if (active.isStreaming && active.msSinceLastFrame() <= APP_STREAM_STALL_MS) {
                log("→ Apps: $label stream healthy after launch")
                return@postDelayed
            }

            log(
                "→ Apps: $label stream stalled after launch " +
                    "(stream=${active.isStreaming}, lastFrame=${active.msSinceLastFrame()}ms); reconnecting once",
            )
            Toast.makeText(
                applicationContext,
                uiText("The bike video stalled; reconnecting automatically…"),
                Toast.LENGTH_LONG,
            ).show()
            active.forceReconnect()

            appProjectionHandler.postDelayed({
                val recovered = active.isStreaming && active.msSinceLastFrame() <= APP_STREAM_STALL_MS
                if (recovered) {
                    log("→ Apps: $label stream recovered")
                } else {
                    log("→ Apps: $label stream did not recover; rider should Stop and reconnect")
                    Toast.makeText(
                        applicationContext,
                        uiText("The app is open, but the TFT video did not recover. Tap Stop and reconnect."),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }, APP_STREAM_RECOVERY_CHECK_MS)
        }, APP_STREAM_POST_LAUNCH_CHECK_MS)
    }

'''
        if clear_anchor not in text:
            raise RuntimeError("MainActivity: clearPendingApp anchor missing")
        text = text.replace(clear_anchor, health_method + clear_anchor, 1)

    # Cancel stale poll callbacks on cancellation/mode change. The already-scheduled post-launch
    # health check intentionally uses its own captured prober and is not cancelled here.
    clear_body = (
        "    private fun clearPendingApp() {\n"
        "        pendingAppPackage = null\n"
    )
    clear_body_new = (
        "    private fun clearPendingApp() {\n"
        "        appProjectionWaitToken++\n"
        "        pendingAppPackage = null\n"
    )
    if clear_body in text:
        text = text.replace(clear_body, clear_body_new, 1)
    elif clear_body_new not in text:
        raise RuntimeError("MainActivity: clearPendingApp body changed")

    companion_anchor = "    companion object {\n"
    constants = (
        "        private const val APP_STREAM_POLL_MS = 250L\n"
        "        private const val APP_STREAM_START_TIMEOUT_MS = 25_000L\n"
        "        private const val APP_STREAM_POST_LAUNCH_CHECK_MS = 4_000L\n"
        "        private const val APP_STREAM_RECOVERY_CHECK_MS = 8_000L\n"
        "        private const val APP_STREAM_STALL_MS = 3_500L\n"
    )
    if "APP_STREAM_START_TIMEOUT_MS" not in text:
        if companion_anchor not in text:
            raise RuntimeError("MainActivity: companion object missing")
        text = text.replace(companion_anchor, companion_anchor + constants, 1)

    write(rel, text)


def patch_ui_text() -> None:
    rel = "app/src/main/java/dev/zanderp/opencfmoto/UiText.kt"
    path = ROOT / rel
    if not path.exists():
        return
    text = path.read_text(encoding="utf-8")

    regex_anchor = '        Regex("""^Not installed: (.+)$""") to "No instaladas: $1",\n'
    additions = (
        '        Regex("""^Preparing (.+) on the bike…$""") to "Preparando $1 en la moto…",\n'
        '        Regex("""^Couldn\'t transmit (.+): (.+)\\. Open Logs and reconnect\\.$""") to\n'
        '            "No se pudo transmitir $1: $2. Abrí Registros y volvé a conectar.",\n'
    )
    if 'Preparing (.+) on the bike' not in text:
        if regex_anchor not in text:
            raise RuntimeError("UiText: regex anchor missing")
        text = text.replace(regex_anchor, regex_anchor + additions, 1)

    exact_anchor = '    "Cancel" to "Cancelar",\n'
    exact_additions = (
        '    "The bike video stalled; reconnecting automatically…" to\n'
        '        "El video de la moto se detuvo; reconectando automáticamente…",\n'
        '    "The app is open, but the TFT video did not recover. Tap Stop and reconnect." to\n'
        '        "La aplicación está abierta, pero el video del TFT no se recuperó. Tocá Detener y volvé a conectar.",\n'
    )
    if 'The bike video stalled; reconnecting automatically' not in text:
        if exact_anchor not in text:
            raise RuntimeError("UiText: exact anchor missing")
        text = text.replace(exact_anchor, exact_anchor + exact_additions, 1)

    path.write_text(text, encoding="utf-8")


def patch_readme_notice() -> None:
    readme = ROOT / "README.md"
    if readme.exists():
        text = readme.read_text(encoding="utf-8")
        text = text.replace("https://github.com/zanderp/open-cfmoto/releases/latest", "https://github.com/andpower/open-cfmoto-450nk/releases/latest")
        text = text.replace("https://github.com/zanderp/open-cfmoto/releases", "https://github.com/andpower/open-cfmoto-450nk/releases")
        if "## 450NK edition" not in text:
            marker = "## ✨ Features\n"
            section = (
                "## 450NK edition\n\n"
                "Independent, parallel-install edition for the non-touch CFMoto 450NK. It keeps the "
                "upstream Android Auto, Wi-Fi and EasyConn fixes, adds Spanish/English UI resources, "
                "and includes a parked Apps mode. Apps now open only after the TFT has received its "
                "first H.264 frame; the app checks the stream again after launch and performs one "
                "automatic reconnect if it stalls. Protected DRM video may still appear black.\n\n"
                "---\n\n"
            )
            if marker in text:
                text = text.replace(marker, section + marker, 1)
        readme.write_text(text, encoding="utf-8")


def validate() -> None:
    main = read("app/src/main/java/dev/zanderp/opencfmoto/MainActivity.kt")
    assertions = {
        "version": 'versionName = "2.3.0-450nk"' in read("app/build.gradle.kts"),
        "package": 'applicationId = "com.andpower.opencfmoto450nk"' in read("app/build.gradle.kts"),
        "wait method": "private fun waitForAppProjectionStream()" in main,
        "saved path waits": "joinWifi(saved, gateOnAaSteady = false)\n            waitForAppProjectionStream()" in main,
        "scan path waits": "joinWifi(qr, gateOnAaSteady = false)\n            if (pendingAppPackage != null) {\n                waitForAppProjectionStream()" in main,
        "post launch health": "verifyAppProjectionHealth(label)" in main,
        "Apps activity": 'android:name=".AppsActivity"' in read("app/src/main/AndroidManifest.xml"),
        "Android 11 Bluetooth": 'android.permission.BLUETOOTH" android:maxSdkVersion="30"' in read("app/src/main/AndroidManifest.xml"),
    }
    failed = [name for name, ok in assertions.items() if not ok]
    if failed:
        raise RuntimeError("integration validation failed: " + ", ".join(failed))


if __name__ == "__main__":
    patch_build_gradle()
    patch_manifest()
    patch_repo_identity()
    patch_main_activity()
    patch_ui_text()
    patch_readme_notice()
    validate()
    print("OpenCfMoto 450NK upstream 2.0.6 integration patches applied and validated")
