#!/usr/bin/env python3
"""Repair same-file semantic conflicts after merging stable 2.0.13 into the 450NK edition."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8-sig")


def write(rel: str, text: str) -> None:
    (ROOT / rel).write_text(text, encoding="utf-8")


def remove_function_occurrence(text: str, signature: str, occurrence: int) -> str:
    starts = []
    pos = 0
    while True:
        pos = text.find(signature, pos)
        if pos < 0:
            break
        starts.append(pos)
        pos += len(signature)
    if len(starts) <= occurrence:
        return text
    start = starts[occurrence]
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"Malformed function: {signature}")
    depth = 0
    i = brace
    in_string = False
    escaped = False
    while i < len(text):
        ch = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
        else:
            if ch == '"':
                in_string = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    end = i + 1
                    while end < len(text) and text[end] in " \t":
                        end += 1
                    if end < len(text) and text[end] == "\n":
                        end += 1
                    if end < len(text) and text[end] == "\n":
                        end += 1
                    return text[:start] + text[end:]
        i += 1
    raise SystemExit(f"Unclosed function: {signature}")


# Kotlin trailing-lambda syntax would bind the lambda to the final Int parameter. Name the callback.
bridge_path = "app/src/main/java/dev/zanderp/opencfmoto/MediaButtonBridge.kt"
bridge = read(bridge_path)
bridge = bridge.replace(
    "Nk450VolumeGestureDetector { ButtonTimingPrefs.doubleTapMs(context) }",
    "Nk450VolumeGestureDetector(doubleTapWindowMs = { ButtonTimingPrefs.doubleTapMs(context) })",
)
write(bridge_path, bridge)


# The edition's MainActivity can keep tile calls while the helper declaration is lost in a same-file
# merge. Restore the helper without touching the custom parked Apps path.
main_path = "app/src/main/java/dev/zanderp/opencfmoto/MainActivity.kt"
main = read(main_path)
if ".asIconTopTile(" in main and "fun MaterialButton.asIconTopTile" not in main:
    marker = "        (findViewById<View>(R.id.btn_aa_start) as? MaterialButton)?.asIconTopTile(R.drawable.ic_qr)\n"
    if marker not in main:
        raise SystemExit("Could not locate MainActivity tile calls")
    helper = '''        fun MaterialButton.asIconTopTile(iconRes: Int) {\n            setIconResource(iconRes)\n            iconGravity = MaterialButton.ICON_GRAVITY_TOP\n            iconPadding = resources.getDimensionPixelSize(R.dimen.btn_tile_icon_padding)\n            maxLines = 1\n            isSingleLine = true\n        }\n'''
    main = main.replace(marker, helper + marker, 1)

# The retained QR transport helper has a GRIFFIN_FIXED mode. Stable upstream added code around the
# same when blocks, so make Griffin follow the normal Wi-Fi path instead of leaving an incomplete
# enum when. This is the conservative behavior: fixed-credential Griffin units are neither KTM
# sharing nor Voge tethering and use the same normal/compat connection machinery as DEFAULT.
if "BikeProfile.Mode.GRIFFIN_FIXED" in main and not re.search(
    r"BikeProfile\.Mode\.GRIFFIN_FIXED,\s*\n\s*BikeProfile\.Mode\.DEFAULT ->",
    main,
):
    main = re.sub(
        r"(?m)^(\s*)BikeProfile\.Mode\.DEFAULT -> \{",
        r"\1BikeProfile.Mode.GRIFFIN_FIXED,\n\1BikeProfile.Mode.DEFAULT -> {",
        main,
    )
write(main_path, main)


# SetupActivity carries the edition's language selector while upstream changes the same section.
# Collapse duplicate helpers and route every newly-added runtime toast through uiText().
setup_path = "app/src/main/java/dev/zanderp/opencfmoto/SetupActivity.kt"
setup = read(setup_path)
sig = "    private fun setTelemetry(on: Boolean) {"
while setup.count(sig) > 1:
    setup = remove_function_occurrence(setup, sig, 1)

runtime_toasts = (
    "Auto-recovery",
    "Bluetooth clock",
    "Keep bike Wi-Fi",
    "Trip logging",
)
for label in runtime_toasts:
    old = f'Toast.makeText(this, "{label} ${{if (on) "on" else "off"}}", Toast.LENGTH_SHORT).show()'
    new = f'Toast.makeText(this, uiText("{label} ${{if (on) "on" else "off"}}"), Toast.LENGTH_SHORT).show()'
    setup = setup.replace(old, new)
write(setup_path, setup)


# Keep those dynamic values genuinely Spanish, not merely validator-compliant.
ui_path = "app/src/main/java/dev/zanderp/opencfmoto/UiText.kt"
ui = read(ui_path)
map_marker = "private val SPANISH_EXACT = mapOf(\n"
if map_marker not in ui:
    raise SystemExit("Could not find SPANISH_EXACT in UiText.kt")
entries = (
    '    "Auto-recovery on" to "Recuperación automática activada",\n',
    '    "Auto-recovery off" to "Recuperación automática desactivada",\n',
    '    "Bluetooth clock on" to "Reloj por Bluetooth activado",\n',
    '    "Bluetooth clock off" to "Reloj por Bluetooth desactivado",\n',
    '    "Keep bike Wi-Fi on" to "Mantener Wi-Fi de la moto activado",\n',
    '    "Keep bike Wi-Fi off" to "Mantener Wi-Fi de la moto desactivado",\n',
    '    "Trip logging on" to "Registro de viajes activado",\n',
    '    "Trip logging off" to "Registro de viajes desactivado",\n',
)
for entry in reversed(entries):
    if entry not in ui:
        ui = ui.replace(map_marker, map_marker + entry, 1)
write(ui_path, ui)


# Sanity gates for the exact failures this script is intended to prevent.
assert "Nk450VolumeGestureDetector(doubleTapWindowMs =" in read(bridge_path)
assert "fun MaterialButton.asIconTopTile" in read(main_path)
assert read(setup_path).count(sig) == 1
assert 'uiText("Auto-recovery ${if (on) "on" else "off"}")' in read(setup_path)
assert 'uiText("Trip logging ${if (on) "on" else "off"}")' in read(setup_path)
print("Semantic merge repairs applied")
