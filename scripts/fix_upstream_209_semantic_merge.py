#!/usr/bin/env python3
"""Repair a few same-file semantic conflicts after merging 2.0.9 into the 450NK edition."""
from pathlib import Path

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


# The edition's MainActivity had a conflict around the tile helpers: calls from 2.0.9 survived while
# the local helper declaration did not. Restore the exact upstream helper without touching Apps mode.
main_path = "app/src/main/java/dev/zanderp/opencfmoto/MainActivity.kt"
main = read(main_path)
if ".asIconTopTile(" in main and "fun MaterialButton.asIconTopTile" not in main:
    marker = "        (findViewById<View>(R.id.btn_aa_start) as? MaterialButton)?.asIconTopTile(R.drawable.ic_qr)\n"
    if marker not in main:
        raise SystemExit("Could not locate MainActivity tile calls")
    helper = '''        fun MaterialButton.asIconTopTile(iconRes: Int) {\n            setIconResource(iconRes)\n            iconGravity = MaterialButton.ICON_GRAVITY_TOP\n            iconPadding = resources.getDimensionPixelSize(R.dimen.btn_tile_icon_padding)\n            maxLines = 1\n            isSingleLine = true\n        }\n'''
    main = main.replace(marker, helper + marker, 1)
write(main_path, main)


# SetupActivity carried the edition's language selector while upstream changed the same section.
# Preserve the edition implementation but collapse any duplicate setTelemetry function produced by merge.
setup_path = "app/src/main/java/dev/zanderp/opencfmoto/SetupActivity.kt"
setup = read(setup_path)
sig = "    private fun setTelemetry(on: Boolean) {"
while setup.count(sig) > 1:
    setup = remove_function_occurrence(setup, sig, 1)
write(setup_path, setup)


# Sanity gates for the exact failures this script is intended to prevent.
assert "Nk450VolumeGestureDetector(doubleTapWindowMs =" in read(bridge_path)
assert "fun MaterialButton.asIconTopTile" in read(main_path)
assert read(setup_path).count(sig) == 1
print("Semantic merge repairs applied")
