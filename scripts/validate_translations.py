#!/usr/bin/env python3
"""Fail CI when the Spanish UI falls behind the English source resources."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"


def strings_in(directory: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for path in sorted(directory.glob("strings*.xml")):
        root = ET.parse(path).getroot()
        for node in root.findall("string"):
            name = node.attrib["name"]
            if name in values:
                raise ValueError(f"duplicate string {name!r} in {directory}")
            values[name] = "".join(node.itertext())
    return values


def placeholders(value: str) -> list[str]:
    return sorted(re.findall(r"%(?:\d+\$)?[a-zA-Z]", value))


def main() -> int:
    english = strings_in(RES / "values")
    spanish = strings_in(RES / "values-es")
    errors: list[str] = []

    missing = sorted(set(english) - set(spanish))
    extra = sorted(set(spanish) - set(english))
    if missing:
        errors.append("missing Spanish resources: " + ", ".join(missing))
    if extra:
        errors.append("Spanish-only resources without English fallback: " + ", ".join(extra))
    for name in sorted(set(english) & set(spanish)):
        if placeholders(english[name]) != placeholders(spanish[name]):
            errors.append(
                f"placeholder mismatch in {name}: "
                f"{placeholders(english[name])} != {placeholders(spanish[name])}"
            )

    # All user-facing Android layout copy must reference a string resource.
    attr = re.compile(r'android:(?:text|hint|contentDescription|title|summary)="([^"]+)"')
    for directory in sorted(RES.glob("layout*")):
        for path in sorted(directory.glob("*.xml")):
            for value in attr.findall(path.read_text()):
                resolution_only = re.fullmatch(r"\d+[×x]\d+", value)
                if (
                    value
                    and not value.startswith(("@", "?", "#"))
                    and not resolution_only
                    and re.search(r"[A-Za-zÁ-ÿ]", value)
                ):
                    errors.append(f"hardcoded layout text in {path.relative_to(ROOT)}: {value!r}")

    # Runtime strings must pass through uiText at the principal UI sinks.
    kotlin_root = ROOT / "app/src/main/java/dev/zanderp/opencfmoto"
    raw_sink = re.compile(
        r'\.(?:setTitle|setMessage|setPositiveButton|setNegativeButton|setNeutralButton)\(\s*"'
        r'|\.text\s*=\s*"'
        r'|Toast\.makeText\([^,\n]+,\s*"'
    )
    for path in sorted(kotlin_root.glob("*.kt")):
        if path.name == "UiText.kt":
            continue
        for line_no, line in enumerate(path.read_text().splitlines(), 1):
            if raw_sink.search(line):
                errors.append(
                    f"runtime UI text bypasses uiText in {path.relative_to(ROOT)}:{line_no}"
                )

    if errors:
        print("Translation validation failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(
        f"Translation validation passed: {len(english)} English and "
        f"{len(spanish)} Spanish string resources."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
