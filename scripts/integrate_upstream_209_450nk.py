#!/usr/bin/env python3
"""Apply the 450NK edition invariants after merging OpenCfMoto upstream 2.0.9."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8-sig")


def write(rel: str, text: str) -> None:
    (ROOT / rel).write_text(text, encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def sub_once(pattern: str, replacement: str, text: str, *, flags: int = 0, label: str = "pattern") -> str:
    out, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    require(count == 1, f"Could not patch {label}: expected 1 match, got {count}")
    return out


# ── Edition identity / signing ──────────────────────────────────────────────────────────────────
gradle_path = "app/build.gradle.kts"
gradle = read(gradle_path)
gradle = sub_once(r'applicationId\s*=\s*"[^"]+"', 'applicationId = "com.andpower.opencfmoto450nk"', gradle, label="applicationId")
gradle = sub_once(r'versionCode\s*=\s*\d+', 'versionCode = 40', gradle, label="versionCode")
gradle = sub_once(r'versionName\s*=\s*"[^"]+"', 'versionName = "2.4.0-450nk"', gradle, label="versionName")

# This edition deliberately keeps anonymous telemetry disabled by default.
gradle = re.sub(
    r'(val telemetryUrl\s*=.*?\n\s*\?:\s*System\.getenv\("TELEMETRY_URL"\)\s*\n\s*\?:\s*)"[^"]*"',
    r'\1""',
    gradle,
    count=1,
    flags=re.S,
)

# Upstream 2.0.8+ includes the git hash in shared logs. Keep the field even though our Gradle file
# also contains the independent signing configuration.
if 'buildConfigField("String", "GIT_HASH"' not in gradle:
    needle = '        buildConfigField("String", "TELEMETRY_URL", "\\\"$telemetryUrl\\\"")\n'
    require(needle in gradle, "Could not find TELEMETRY_URL buildConfigField")
    git_hash = '''\n        val gitHash = providers.exec {\n            commandLine("git", "rev-parse", "--short", "HEAD")\n            workingDir(rootProject.projectDir)\n            isIgnoreExitValue = true\n        }.standardOutput.asText.map { text ->\n            val t = text.trim()\n            if (t.matches(Regex("[0-9a-f]{4,40}"))) t else "unknown"\n        }.orElse("unknown")\n        buildConfigField("String", "GIT_HASH", "\\\"${gitHash.get()}\\\"")\n'''
    gradle = gradle.replace(needle, needle + git_hash, 1)

require('create("permanentRelease")' in gradle, "Permanent signing config was lost during upstream merge")
require('signingConfig = signingConfigs.getByName("permanentRelease")' in gradle, "Release signing assignment was lost")
write(gradle_path, gradle)


# ── Update checker must point at our edition, never the upstream releases ───────────────────────
update_path = "app/src/main/java/dev/zanderp/opencfmoto/UpdateChecker.kt"
update = read(update_path)
update = sub_once(r'const val REPO\s*=\s*"[^"]+"', 'const val REPO = "andpower/open-cfmoto-450nk"', update, label="UpdateChecker.REPO")
write(update_path, update)


# ── 450NK hybrid controls ───────────────────────────────────────────────────────────────────────
bridge_path = "app/src/main/java/dev/zanderp/opencfmoto/MediaButtonBridge.kt"
bridge = read(bridge_path)

if "nk450HybridControls" not in bridge:
    fields_needle = "    private var userVolume = -1\n"
    require(fields_needle in bridge, "Could not find MediaButtonBridge userVolume field")
    fields = '''    /** 450NK edition: short ▲/▼ stays volume, hold stays media track, ×2 navigates MotoPlay/AA. */\n    private val nk450HybridControls = BuildConfig.APPLICATION_ID == "com.andpower.opencfmoto450nk"\n    private var nk450ObservedVolume = -1\n    private val nk450VolumeDetector = Nk450VolumeGestureDetector { ButtonTimingPrefs.doubleTapMs(context) }\n'''
    bridge = bridge.replace(fields_needle, fields_needle + fields, 1)

# Never pin/hijack STREAM_MUSIC on the 450NK. We observe it, but a single press must actually change
# the listening volume.
maybe_pin_marker = "    private fun maybePinVolume(reason: String) {\n"
require(maybe_pin_marker in bridge, "Could not find maybePinVolume")
if "450NK hybrid — short rocker presses remain real volume" not in bridge:
    bridge = bridge.replace(
        maybe_pin_marker,
        maybe_pin_marker
        + '''        if (nk450HybridControls) {\n            if (pinnedVolume >= 0 || userVolume >= 0) unpinVolume()\n            log("[BTN] 450NK hybrid — short rocker presses remain real volume ($reason)")\n            return\n        }\n''',
        1,
    )

# Initialize the observed level, then branch before the upstream pinned-volume path.
start_observer_marker = "    private fun startVolumeObserver() {\n        if (volumeObserver != null) return\n"
require(start_observer_marker in bridge, "Could not find startVolumeObserver")
if "nk450ObservedVolume = try" not in bridge:
    bridge = bridge.replace(
        start_observer_marker,
        start_observer_marker
        + '''        if (nk450HybridControls) {\n            nk450ObservedVolume = try { audio.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { -1 }\n            nk450VolumeDetector.reset()\n        }\n''',
        1,
    )

old_gate = "                if (!ButtonMode.isControlAa(context) || pinnedVolume < 0) return\n                if (now == pinnedVolume) return   // our own re-pin, or nothing to do\n"
require(old_gate in bridge, "Could not find upstream volume-observer gate")
bridge = bridge.replace(
    old_gate,
    '''                if (!ButtonMode.isControlAa(context)) {\n                    if (nk450HybridControls) nk450ObservedVolume = now\n                    return\n                }\n                if (nk450HybridControls) {\n                    handleNk450VolumeChange(now)\n                    return\n                }\n                if (pinnedVolume < 0) return\n                if (now == pinnedVolume) return   // our own re-pin, or nothing to do\n''',
    1,
)

stop_observer_marker = "    private fun stopVolumeObserver() {\n"
require(stop_observer_marker in bridge, "Could not find stopVolumeObserver")
if "private fun handleNk450VolumeChange" not in bridge:
    helper = '''    /**\n     * 450NK: one ▲/▼ is left untouched as real volume. A second same-direction press inside the\n     * configured window (or a coalesced large AVRCP jump) becomes the navigation gesture and the\n     * volume is restored to the level from before the pair.\n     */\n    private fun handleNk450VolumeChange(now: Int) {\n        val previous = nk450ObservedVolume\n        nk450ObservedVolume = now\n        val decision = nk450VolumeDetector.onVolumeChange(previous, now, SystemClock.elapsedRealtime()) ?: return\n        ButtonPresencePrefs.markVolumeSeen(context)\n        cancelAbsentVolumeProbe()\n        val dir = if (decision.direction > 0) "UP" else "DOWN"\n        when (decision.kind) {\n            Nk450VolumeGestureDetector.Kind.SINGLE_VOLUME ->\n                log("[BTN] 450NK volume $dir $previous→$now — single kept as volume")\n            Nk450VolumeGestureDetector.Kind.DOUBLE_NAVIGATION -> {\n                decision.restoreVolume?.let(::restoreNk450Volume)\n                val gesture = if (decision.direction > 0) {\n                    ButtonGesture.NAV_BACK_DOUBLE\n                } else {\n                    ButtonGesture.NAV_FWD_DOUBLE\n                }\n                log("[BTN] 450NK volume $dir ×2 — MotoPlay/AA navigation")\n                run(gesture)\n            }\n        }\n    }\n\n    private fun restoreNk450Volume(level: Int) {\n        val max = try { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 15 }\n        val target = level.coerceIn(0, max)\n        ignoreVolumeChanges = true\n        nk450ObservedVolume = target\n        try {\n            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)\n        } catch (_: Exception) {\n        } finally {\n            handler.postDelayed({ ignoreVolumeChanges = false }, 80)\n        }\n    }\n\n'''
    bridge = bridge.replace(stop_observer_marker, helper + stop_observer_marker, 1)

# The 450NK definitely has the rocker, so do not let the generic 90 s "absent" probe disable it.
probe_marker = "    private fun startAbsentVolumeProbe() {\n        cancelAbsentVolumeProbe()\n"
require(probe_marker in bridge, "Could not find startAbsentVolumeProbe")
if "if (nk450HybridControls) return" not in bridge[bridge.index(probe_marker):bridge.index(probe_marker) + 300]:
    bridge = bridge.replace(probe_marker, probe_marker + "        if (nk450HybridControls) return\n", 1)

# Forward discrete previous/next transport events (the firmware's long ▲/▼ gesture) to the actual
# music player. We momentarily deactivate our MediaSession to avoid dispatching the synthetic event
# back into ourselves, then reclaim the session for MotoPlay/AA selection.
key_down_marker = "    private fun onKeyDown(keyCode: Int, repeatCount: Int = 0) {\n        val held = heldFor(keyCode) ?: return\n        ButtonPresencePrefs.markTrackSeen(context)\n        lastKeyAt = SystemClock.elapsedRealtime()\n"
require(key_down_marker in bridge, "Could not find onKeyDown")
if "450NK long rocker" not in bridge:
    bridge = bridge.replace(
        key_down_marker,
        key_down_marker
        + '''        if (nk450HybridControls && held !== heldSelect && repeatCount > 0) {\n            if (!held.longFromRepeat) {\n                held.longFromRepeat = true\n                held.downAt = 0L\n                log("[BTN] 450NK long rocker repeat — forwarding previous/next to music")\n                dispatchNk450NativeTrack(held)\n            }\n            return\n        }\n''',
        1,
    )

key_up_marker = "    private fun onKeyUp(keyCode: Int) {\n        val held = heldFor(keyCode) ?: return\n        lastKeyAt = SystemClock.elapsedRealtime()\n"
require(key_up_marker in bridge, "Could not find onKeyUp")
if "450NK discrete track event" not in bridge:
    bridge = bridge.replace(
        key_up_marker,
        key_up_marker
        + '''        if (nk450HybridControls && held !== heldSelect) {\n            if (held.longFromRepeat) {\n                held.reset()\n                return\n            }\n            val hadDown = held.downAt != 0L\n            held.reset()\n            if (hadDown) {\n                log("[BTN] 450NK discrete track event — forwarding previous/next to music")\n                dispatchNk450NativeTrack(held)\n            }\n            return\n        }\n''',
        1,
    )

on_key_down_pos = bridge.index("    private fun onKeyDown(keyCode: Int")
if "private fun dispatchNk450NativeTrack" not in bridge:
    dispatch = '''    private fun dispatchNk450NativeTrack(held: HeldButton) {\n        val keyCode = if (held === heldBack) KeyEvent.KEYCODE_MEDIA_PREVIOUS else KeyEvent.KEYCODE_MEDIA_NEXT\n        try {\n            session?.isActive = false\n            val t = SystemClock.uptimeMillis()\n            audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))\n            audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))\n            handler.postDelayed({\n                if (ButtonMode.isControlAa(context)) {\n                    try {\n                        session?.isActive = true\n                        refreshPlayingAppearance(reason = "450nk-native-track")\n                    } catch (e: Exception) {\n                        log("[BTN] 450NK media-session reclaim failed: $e")\n                    }\n                }\n            }, 250L)\n        } catch (e: Exception) {\n            log("[BTN] 450NK native track dispatch failed: $e")\n            if (ButtonMode.isControlAa(context)) session?.isActive = true\n        }\n    }\n\n'''
    bridge = bridge[:on_key_down_pos] + dispatch + bridge[on_key_down_pos:]

bridge = bridge.replace(
    '"◀ ▶ knob · ×2 ←→ · ★ OK · ★★ Back · ★hold Home"',
    '"450NK: ▲/▼ volumen · mantener pista · ×2 navegar · ★ OK"',
)
write(bridge_path, bridge)


# ── Release workflow ─────────────────────────────────────────────────────────────────────────────
workflow_path = ".github/workflows/android.yml"
workflow = read(workflow_path)
workflow = workflow.replace("default: v2.3.1-450nk", "default: v2.4.0-450nk")
workflow = workflow.replace("if: github.event_name == 'workflow_dispatch'\n        env:\n          GH_TOKEN", "if: github.event_name == 'workflow_dispatch' || github.ref == 'refs/heads/main'\n        env:\n          GH_TOKEN")
workflow = workflow.replace("RELEASE_TAG: ${{ inputs.release_tag }}", "RELEASE_TAG: ${{ inputs.release_tag || 'v2.4.0-450nk' }}")
workflow = re.sub(r'--title "OpenCfMoto 450NK [^"]+"', '--title "OpenCfMoto 450NK 2.4.0 — upstream 2.0.9 + controles 450NK"', workflow, count=1)
workflow = re.sub(
    r'--notes "[\s\S]*?"\n$',
    '--notes "Actualización basada en OpenCfMoto 2.0.9.\\n\\nIncluye mejoras de conexión, SoftAP/hotspot, Teach my handlebar, perfiles de tablero, recuperación y sincronización segura del reloj.\\n\\nMantiene la lógica física de la 450NK: toque corto ▲/▼ = volumen, mantener ▲/▼ = canción anterior/siguiente y doble toque = navegación MotoPlay/Android Auto.\\n\\nConserva Apps estacionadas, Español/English, identificador com.andpower.opencfmoto450nk, icono propio y firma permanente."\n',
    workflow,
    count=1,
)
write(workflow_path, workflow)


# ── Sanity checks ────────────────────────────────────────────────────────────────────────────────
require('versionName = "2.4.0-450nk"' in read(gradle_path), "Version patch did not stick")
require('const val REPO = "andpower/open-cfmoto-450nk"' in read(update_path), "Update checker points elsewhere")
require("handleNk450VolumeChange" in read(bridge_path), "450NK hybrid control patch missing")
require("dispatchNk450NativeTrack" in read(bridge_path), "450NK long-press media forwarding missing")
require("v2.4.0-450nk" in read(workflow_path), "Release workflow not updated")
print("Applied OpenCfMoto 2.0.9 + 450NK edition invariants for 2.4.0-450nk")
