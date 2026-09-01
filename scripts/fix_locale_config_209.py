#!/usr/bin/env python3
"""Keep the edition's explicit locale_config.xml and disable duplicate AGP generation."""
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "app/build.gradle.kts"
text = path.read_text(encoding="utf-8-sig")
block = "    androidResources {\n        generateLocaleConfig = true\n    }\n"
if block in text:
    text = text.replace(block, "", 1)
if "generateLocaleConfig = true" in text:
    raise SystemExit("Automatic locale config is still enabled")
path.write_text(text, encoding="utf-8")
print("Using explicit res/xml/locales_config.xml; automatic locale generation disabled")
