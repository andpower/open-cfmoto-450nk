#!/usr/bin/env python3
"""Wrap OpenCfMoto 2.0.5/2.0.6 runtime strings in the 450NK bilingual UI bridge."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0:
        if new in text:
            return text
        raise RuntimeError(f"{label}: source and replacement are both missing")
    if count != 1:
        raise RuntimeError(f"{label}: expected one source match, found {count}")
    return text.replace(old, new, 1)


def patch_manual_pairing() -> None:
    path = ROOT / "app/src/main/java/dev/zanderp/opencfmoto/ManualWifiPairing.kt"
    text = path.read_text(encoding="utf-8")
    replacements = [
        ('val ssid = field("Wi‑Fi name (SSID)")', 'val ssid = field(activity.uiText("Wi‑Fi name (SSID)"))'),
        ('val pwd = field("Password", password = true)', 'val pwd = field(activity.uiText("Password"), password = true)'),
        ('val name = field("Bike name (optional)")', 'val name = field(activity.uiText("Bike name (optional)"))'),
        ('.setTitle("Enter bike Wi‑Fi")', '.setTitle(activity.uiText("Enter bike Wi‑Fi"))'),
        (
            '.setMessage(\n                "For dashes that show SSID + password instead of a QR (e.g. Benelli TRK). " +\n                    "Use the exact network name from the bike screen.",\n            )',
            '.setMessage(\n                activity.uiText(\n                    "For dashes that show SSID + password instead of a QR (e.g. Benelli TRK). " +\n                        "Use the exact network name from the bike screen.",\n                ),\n            )',
        ),
        ('.setPositiveButton("Add bike", null)', '.setPositiveButton(activity.uiText("Add bike"), null)'),
        ('.setNegativeButton("Cancel", null)', '.setNegativeButton(activity.uiText("Cancel"), null)'),
        (
            'Toast.makeText(activity, "SSID and password are required", Toast.LENGTH_SHORT)',
            'Toast.makeText(activity, activity.uiText("SSID and password are required"), Toast.LENGTH_SHORT)',
        ),
    ]
    for old, new in replacements:
        text = replace_once(text, old, new, f"ManualWifiPairing {old[:32]}")
    path.write_text(text, encoding="utf-8")


def patch_garage() -> None:
    path = ROOT / "app/src/main/java/dev/zanderp/opencfmoto/GarageActivity.kt"
    text = path.read_text(encoding="utf-8")
    old = 'Toast.makeText(this, "Added ${BikeMemory.lastBikeName(this)}", Toast.LENGTH_SHORT)'
    new = 'Toast.makeText(this, uiText("Added ${BikeMemory.lastBikeName(this)}"), Toast.LENGTH_SHORT)'
    text = replace_once(text, old, new, "GarageActivity manual bike toast")
    path.write_text(text, encoding="utf-8")


def patch_ui_text() -> None:
    path = ROOT / "app/src/main/java/dev/zanderp/opencfmoto/UiText.kt"
    if not path.exists():
        raise RuntimeError("UiText.kt is required by the 450NK bilingual edition")
    text = path.read_text(encoding="utf-8")
    anchor = '    "Cancel" to "Cancelar",\n'
    additions = (
        '    "Wi‑Fi name (SSID)" to "Nombre de Wi‑Fi (SSID)",\n'
        '    "Password" to "Contraseña",\n'
        '    "Bike name (optional)" to "Nombre de la moto (opcional)",\n'
        '    "Enter bike Wi‑Fi" to "Ingresar Wi‑Fi de la moto",\n'
        '    "For dashes that show SSID + password instead of a QR (e.g. Benelli TRK). Use the exact network name from the bike screen." to\n'
        '        "Para tableros que muestran SSID y contraseña en lugar de un QR. Usá el nombre exacto de la red que aparece en la pantalla de la moto.",\n'
        '    "Add bike" to "Agregar moto",\n'
        '    "SSID and password are required" to "El SSID y la contraseña son obligatorios",\n'
    )
    if '"Enter bike Wi‑Fi" to "Ingresar Wi‑Fi de la moto"' not in text:
        if anchor not in text:
            raise RuntimeError("UiText Cancel anchor not found")
        text = text.replace(anchor, anchor + additions, 1)
    path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    patch_manual_pairing()
    patch_garage()
    patch_ui_text()
    print("Upstream 2.0.6 runtime strings wrapped and translated")
