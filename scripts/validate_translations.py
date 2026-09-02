#!/usr/bin/env python3
"""Fail CI when the Spanish UI falls behind the English source resources.

The upstream application still contains a small amount of intentional runtime and
technical layout copy.  This validator focuses on the guarantees this fork owns:
complete English/Spanish resources, valid XML, unique names, and matching printf
placeholders.
"""

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
