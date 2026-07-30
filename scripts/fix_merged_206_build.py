#!/usr/bin/env python3
"""Repair resource/layout merge artifacts after importing upstream OpenCfMoto 2.0.6.

The 450NK edition had extracted layout strings and bilingual overrides while upstream 2.0.6
introduced a much larger semantic resource catalogue. Git's conflict preference preserved the
450NK files, so Kotlin sources from upstream referenced resources that were absent. This script:

* imports every missing upstream resource without overwriting 450NK translations/branding;
* repairs three layouts that were structurally valid XML but lost controls during conflict merge;
* completes the Apps stream-health implementation and handlebar-hold settings wiring;
* validates every edited XML file and the required runtime identifiers.

It is deliberately idempotent and fails loudly when the source layout changes unexpectedly.
"""
from __future__ import annotations

import copy
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def git_show(path: str) -> str:
    result = subprocess.run(
        ["git", "show", f"upstream/main:{path}"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout


def named_resources(directory: Path, exclude: Path | None = None) -> set[tuple[str, str]]:
    found: set[tuple[str, str]] = set()
    if not directory.exists():
        return found
    for path in sorted(directory.glob("*.xml")):
        if exclude is not None and path.resolve() == exclude.resolve():
            continue
        root = ET.parse(path).getroot()
        for child in root:
            name = child.attrib.get("name")
            if name:
                found.add((child.tag, name))
    return found


def indent_xml(root: ET.Element) -> str:
    ET.indent(root, space="    ")
    body = ET.tostring(root, encoding="unicode", short_empty_elements=True)
    return '<?xml version="1.0" encoding="utf-8"?>\n' + body + "\n"


def import_upstream_resources() -> None:
    """Import missing default/Spanish resources while preserving local overrides."""
    default_target = RES / "values/upstream_206_import.xml"
    spanish_target = RES / "values-es/upstream_206_import.xml"

    upstream_default = ET.fromstring(
        git_show("app/src/main/res/values/strings.xml")
    )
    try:
        upstream_spanish = ET.fromstring(
            git_show("app/src/main/res/values-es/strings.xml")
        )
    except subprocess.CalledProcessError:
        upstream_spanish = ET.Element("resources")

    existing_default = named_resources(RES / "values", default_target)
    existing_spanish = named_resources(RES / "values-es", spanish_target)
    spanish_by_key = {
        (child.tag, child.attrib.get("name", "")): child
        for child in upstream_spanish
        if child.attrib.get("name")
    }

    imported_default: list[ET.Element] = []
    imported_spanish: list[ET.Element] = []
    for child in upstream_default:
        name = child.attrib.get("name")
        if not name:
            continue
        key = (child.tag, name)
        if key not in existing_default:
            imported_default.append(copy.deepcopy(child))
        if key not in existing_spanish:
            translated = spanish_by_key.get(key)
            imported_spanish.append(copy.deepcopy(translated if translated is not None else child))

    default_root = ET.Element("resources")
    default_root.append(ET.Comment("Missing OpenCfMoto 2.0.6 resources; local 450NK overrides win."))
    for child in imported_default:
        default_root.append(child)
    spanish_root = ET.Element("resources")
    spanish_root.append(ET.Comment("Spanish OpenCfMoto 2.0.6 resources; English fallback only when upstream lacks Spanish."))
    for child in imported_spanish:
        spanish_root.append(child)

    default_target.parent.mkdir(parents=True, exist_ok=True)
    spanish_target.parent.mkdir(parents=True, exist_ok=True)
    default_target.write_text(indent_xml(default_root), encoding="utf-8")
    spanish_target.write_text(indent_xml(spanish_root), encoding="utf-8")


def replace_exact(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one source match, found {count}")
    return text.replace(old, new, 1)


def patch_main_activity() -> None:
    rel = "app/src/main/java/dev/zanderp/opencfmoto/MainActivity.kt"
    text = read(rel)

    # The direct icon setup immediately above these calls already does the same work. The helper was
    # lost in the merge, so retaining the calls only creates an unresolved symbol.
    for line in (
        '        (findViewById<View>(R.id.btn_aa_start) as? MaterialButton)?.asIconTopTile(R.drawable.ic_qr)\n',
        '        (findViewById<View>(R.id.btn_gpx) as? MaterialButton)?.asIconTopTile(R.drawable.ic_place)\n',
        '        (findViewById<View>(R.id.btn_mirror_start) as? MaterialButton)?.asIconTopTile(R.drawable.ic_cast)\n',
    ):
        text = text.replace(line, "")

    text = replace_exact(
        text,
        "statusView.text = uiText(phase.label)",
        "statusView.text = getString(phase.labelRes)",
        "localized connection phase",
    )
    text = replace_exact(
        text,
        "val label = pendingAppLabel ?: packageName",
        "val label = pendingAppLabel ?: packageName.orEmpty()",
        "non-null Apps label",
    )

    companion = "    companion object {\n"
    constants = (
        "        private const val APP_STREAM_POLL_MS = 250L\n"
        "        private const val APP_STREAM_START_TIMEOUT_MS = 25_000L\n"
        "        private const val APP_STREAM_POST_LAUNCH_CHECK_MS = 4_000L\n"
        "        private const val APP_STREAM_RECOVERY_CHECK_MS = 8_000L\n"
        "        private const val APP_STREAM_STALL_MS = 3_500L\n"
    )
    if "private const val APP_STREAM_POLL_MS" not in text:
        if text.count(companion) != 1:
            raise RuntimeError("MainActivity companion object anchor changed")
        text = text.replace(companion, companion + constants, 1)

    write(rel, text)


def patch_setup_activity() -> None:
    rel = "app/src/main/java/dev/zanderp/opencfmoto/SetupActivity.kt"
    text = read(rel)

    method = '''    private fun setHoldsEnabled(on: Boolean) {
        ButtonTimingPrefs.setHoldsEnabled(this, on)
        refreshOptions()
        Toast.makeText(
            this,
            uiText(if (on) "Hold gestures enabled" else "Hold gestures disabled"),
            Toast.LENGTH_SHORT,
        ).show()
    }

'''
    anchor = "    private fun setLongPress(delay: LongPressDelay) {\n"
    if "private fun setHoldsEnabled(on: Boolean)" not in text:
        if text.count(anchor) != 1:
            raise RuntimeError("SetupActivity setLongPress anchor changed")
        text = text.replace(anchor, method + anchor, 1)

    hold_desc_line = "        holdsDesc.text = getString(R.string.holds_desc)\n"
    refresh_anchor = "        holdDesc.text = uiText(hold.label)\n"
    if hold_desc_line not in text:
        if text.count(refresh_anchor) != 1:
            raise RuntimeError("SetupActivity hold description anchor changed")
        text = text.replace(refresh_anchor, hold_desc_line + refresh_anchor, 1)

    write(rel, text)


def patch_main_layout() -> None:
    rel = "app/src/main/res/layout/activity_main.xml"
    text = read(rel)
    footer = '''            <!-- 2×2 so labels stay horizontal on narrow phones / large font. -->
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="6dp"
                android:orientation="vertical">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:baselineAligned="false"
                    android:orientation="horizontal">

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/btn_setup"
                        style="@style/Widget.OpenCfMoto.Button.Text"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_marginEnd="4dp"
                        android:layout_weight="1"
                        android:text="@string/layout_setup_cdd7bb2" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/btn_devices"
                        style="@style/Widget.OpenCfMoto.Button.Text"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="4dp"
                        android:layout_weight="1"
                        android:text="@string/layout_garage_f614ac3" />
                </LinearLayout>

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:baselineAligned="false"
                    android:orientation="horizontal">

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/btn_trip"
                        style="@style/Widget.OpenCfMoto.Button.Text"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_marginEnd="4dp"
                        android:layout_weight="1"
                        android:text="@string/layout_trip_9dda9aa" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/btn_toggle_log"
                        style="@style/Widget.OpenCfMoto.Button.Text"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="4dp"
                        android:layout_weight="1"
                        android:text="@string/layout_logs_126dd3b" />
                </LinearLayout>
            </LinearLayout>

'''
    pattern = re.compile(
        r'            <!-- 2×2 so labels stay horizontal on narrow phones / large font\. -->\n'
        r'.*?'
        r'(?=            <LinearLayout\n'
        r'                android:layout_width="match_parent"\n'
        r'                android:layout_height="wrap_content"\n'
        r'                android:layout_marginTop="8dp"\n'
        r'                android:gravity="center_vertical")',
        re.DOTALL,
    )
    if 'android:id="@+id/btn_toggle_log"' not in text:
        text, count = pattern.subn(footer, text, count=1)
        if count != 1:
            raise RuntimeError("activity_main footer merge-artifact block not found")
    write(rel, text)


def patch_garage_layout() -> None:
    rel = "app/src/main/res/layout/activity_garage.xml"
    text = read(rel)
    controls = '''    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_margin="16dp"
        android:baselineAligned="false"
        android:orientation="horizontal">

        <com.google.android.material.button.MaterialButton
            android:id="@+id/garage_scan"
            style="@style/Widget.OpenCfMoto.Button.Tonal"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginEnd="4dp"
            android:layout_weight="1"
            android:text="@string/layout_scan_new_bike_495a919"
            app:icon="@drawable/ic_qr" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/garage_manual"
            style="@style/Widget.OpenCfMoto.Button.Tonal"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="4dp"
            android:layout_weight="1"
            android:text="@string/garage_enter_wi_fi_manually" />
    </LinearLayout>
'''
    if 'android:id="@+id/garage_manual"' not in text:
        pattern = re.compile(
            r'    <LinearLayout\n'
            r'        android:layout_width="match_parent"\n'
            r'        android:layout_height="wrap_content"\n'
            r'        android:layout_margin="16dp"\n'
            r'        android:text="@string/layout_scan_new_bike_495a919"\n'
            r'        app:icon="@drawable/ic_qr" />\n',
        )
        text, count = pattern.subn(controls, text, count=1)
        if count != 1:
            raise RuntimeError("activity_garage malformed controls block not found")
    write(rel, text)


def patch_qr_layout() -> None:
    rel = "app/src/main/res/layout/activity_qr_scan.xml"
    text = read(rel)
    panel = '''    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:background="#99000000"
        android:orientation="vertical"
        android:paddingHorizontal="16dp"
        android:paddingTop="12dp"
        android:paddingBottom="14dp">

        <TextView
            android:id="@+id/hint"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/layout_point_the_camera_at_the_b09078b"
            android:textAlignment="center"
            android:textColor="#FFFFFF"
            android:textSize="13sp"
            android:lineSpacingExtra="2dp" />

        <Button
            android:id="@+id/btn_manual_wifi"
            style="@style/Widget.Material3.Button.TextButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:text="@string/garage_enter_wi_fi_manually"
            android:textColor="#FFB74D" />
    </LinearLayout>
'''
    if 'android:id="@+id/btn_manual_wifi"' not in text:
        old = '''    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:background="#99000000"
        android:orientation="vertical"
        android:paddingHorizontal="16dp"
        android:paddingTop="12dp"
        android:paddingBottom="14dp"
        android:text="@string/layout_point_the_camera_at_the_b09078b"
        android:textAlignment="center"
        android:textColor="#FFFFFF"
        android:textSize="13sp"
        android:lineSpacingExtra="2dp" />
'''
        text = replace_exact(text, old, panel, "activity_qr_scan bottom panel")
    write(rel, text)


def patch_setup_layout() -> None:
    rel = "app/src/main/res/layout/activity_setup.xml"
    text = read(rel)
    block = '''                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="16dp"
                    android:text="@string/holds_desc"
                    android:textColor="@color/text_primary"
                    android:textSize="16sp"
                    android:textStyle="bold" />

                <TextView
                    android:id="@+id/holds_desc"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="2dp"
                    android:layout_marginBottom="6dp"
                    android:text="@string/holds_desc"
                    android:textColor="@color/text_secondary"
                    android:textSize="13sp" />

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:baselineAligned="false"
                    android:orientation="horizontal">

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/holds_on"
                        style="@style/Widget.OpenCfMoto.Segment"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_marginEnd="4dp"
                        android:layout_weight="1"
                        android:text="@string/holds_on" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/holds_off"
                        style="@style/Widget.OpenCfMoto.Segment"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="4dp"
                        android:layout_weight="1"
                        android:text="@string/holds_off" />
                </LinearLayout>

'''
    anchor = '''                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="16dp"
                    android:text="@string/layout_hold_delay_81d656c"
'''
    if 'android:id="@+id/holds_desc"' not in text:
        if text.count(anchor) != 1:
            raise RuntimeError("activity_setup hold-delay anchor changed")
        text = text.replace(anchor, block + anchor, 1)
    write(rel, text)


def validate() -> None:
    xml_paths = [
        RES / "values/upstream_206_import.xml",
        RES / "values-es/upstream_206_import.xml",
        RES / "layout/activity_main.xml",
        RES / "layout/activity_garage.xml",
        RES / "layout/activity_qr_scan.xml",
        RES / "layout/activity_setup.xml",
    ]
    for path in xml_paths:
        ET.parse(path)

    main = read("app/src/main/java/dev/zanderp/opencfmoto/MainActivity.kt")
    setup = read("app/src/main/java/dev/zanderp/opencfmoto/SetupActivity.kt")
    required = {
        "Apps constants": "private const val APP_STREAM_START_TIMEOUT_MS" in main,
        "non-null Apps label": "pendingAppLabel ?: packageName.orEmpty()" in main,
        "localized phase": "getString(phase.labelRes)" in main,
        "no lost icon helper": ".asIconTopTile(" not in main,
        "hold setter": "private fun setHoldsEnabled(on: Boolean)" in setup,
        "main logs button": '@+id/btn_toggle_log' in read("app/src/main/res/layout/activity_main.xml"),
        "garage manual": '@+id/garage_manual' in read("app/src/main/res/layout/activity_garage.xml"),
        "QR manual": '@+id/btn_manual_wifi' in read("app/src/main/res/layout/activity_qr_scan.xml"),
        "hold controls": '@+id/holds_on' in read("app/src/main/res/layout/activity_setup.xml"),
    }
    failed = [name for name, ok in required.items() if not ok]
    if failed:
        raise RuntimeError("post-merge validation failed: " + ", ".join(failed))


if __name__ == "__main__":
    import_upstream_resources()
    patch_main_activity()
    patch_setup_activity()
    patch_main_layout()
    patch_garage_layout()
    patch_qr_layout()
    patch_setup_layout()
    validate()
    print("OpenCfMoto 2.0.6 resource/layout merge artifacts repaired and validated")
