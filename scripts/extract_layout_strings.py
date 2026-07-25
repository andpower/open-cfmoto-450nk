#!/usr/bin/env python3
"""Move user-visible Android layout literals into deterministic string resources.

Run from the repository root. The generated resource names include a short content hash,
so repeated English text reuses one resource while later wording changes stay reviewable.
"""

from __future__ import annotations

import hashlib
import re
import unicodedata
from pathlib import Path
from xml.sax.saxutils import escape


RES_ROOT = Path("app/src/main/res")
OUTPUT = RES_ROOT / "values" / "strings_layout.xml"
ATTR = re.compile(
    r'android:(text|hint|contentDescription|title|summary)="'
    r'((?!@string/|\?|@android:|%)[^"]*[A-Za-z][^"]*)"'
)
SYMBOL_ONLY = re.compile(r"^[\d\s.—…←→▲▼◀▶★＋－⟲⟳/]+$")


def resource_name(value: str) -> str:
    plain = re.sub(r"&[A-Za-z#0-9]+;", " ", value)
    plain = unicodedata.normalize("NFKD", plain).encode("ascii", "ignore").decode()
    words = re.findall(r"[a-z0-9]+", plain.lower())[:5]
    stem = "_".join(words) or "text"
    digest = hashlib.sha1(value.encode("utf-8")).hexdigest()[:7]
    return f"layout_{stem}_{digest}"


def xml_text(value: str) -> str:
    # Layout attribute entities must be decoded once before being stored as element text.
    value = (
        value.replace("&quot;", '"')
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#10;", "\n")
    )
    return escape(value).replace("'", r"\'").replace("\n", r"\n")


def main() -> None:
    values: dict[str, str] = {}
    changed = 0

    for path in sorted(RES_ROOT.glob("layout*/*.xml")):
        source = path.read_text(encoding="utf-8")

        def replace(match: re.Match[str]) -> str:
            nonlocal changed
            attr, value = match.groups()
            if SYMBOL_ONLY.fullmatch(value):
                return match.group(0)
            name = resource_name(value)
            previous = values.setdefault(name, value)
            if previous != value:
                raise RuntimeError(f"Hash collision for {name}")
            changed += 1
            return f'android:{attr}="@string/{name}"'

        updated = ATTR.sub(replace, source)
        if updated != source:
            path.write_text(updated, encoding="utf-8")

    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<resources>",
        "    <!-- Generated from layout literals by scripts/extract_layout_strings.py. -->",
    ]
    for name, value in sorted(values.items()):
        lines.append(
            f'    <string name="{name}" formatted="false">{xml_text(value)}</string>'
        )
    lines.append("</resources>")
    OUTPUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Replaced {changed} layout occurrences with {len(values)} resources.")


if __name__ == "__main__":
    main()
