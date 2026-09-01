#!/usr/bin/env python3
"""Merge resource IDs added by stable upstream 2.0.13 without deleting 450NK-specific strings."""
from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM = "26726f102c11eab553f220b61d084f4f123f978c"


def git_show(path: str) -> bytes:
    return subprocess.check_output(["git", "show", f"{UPSTREAM}:{path}"], cwd=ROOT)


def identity(node: ET.Element):
    name = node.attrib.get("name")
    if not name:
        return None
    return node.tag, name


def merge(path: str) -> int:
    target_path = ROOT / path
    target_tree = ET.parse(target_path)
    target_root = target_tree.getroot()
    upstream_root = ET.fromstring(git_show(path).decode("utf-8-sig"))

    existing = {identity(node) for node in target_root if identity(node) is not None}
    added = 0
    for node in upstream_root:
        key = identity(node)
        if key is None or key in existing:
            continue
        target_root.append(deepcopy(node))
        existing.add(key)
        added += 1

    ET.indent(target_tree, space="    ")
    target_tree.write(target_path, encoding="utf-8", xml_declaration=True)
    return added


def keys(path: str) -> set[tuple[str, str]]:
    root = ET.parse(ROOT / path).getroot()
    return {identity(node) for node in root if identity(node) is not None}


english_path = "app/src/main/res/values/strings.xml"
spanish_path = "app/src/main/res/values-es/strings.xml"
en_added = merge(english_path)
es_added = merge(spanish_path)

# The 450NK edition promises a complete EN/ES UI. If upstream has an English resource with no
# Spanish counterpart, copy the default node only as a build-safe fallback and print every such key;
# validation in the next step remains responsible for catching string-resource parity problems.
en = keys(english_path)
es = keys(spanish_path)
missing_es = sorted(en - es)
if missing_es:
    target_tree = ET.parse(ROOT / spanish_path)
    target_root = target_tree.getroot()
    english_root = ET.parse(ROOT / english_path).getroot()
    by_key = {identity(node): node for node in english_root if identity(node) is not None}
    for key in missing_es:
        target_root.append(deepcopy(by_key[key]))
    ET.indent(target_tree, space="    ")
    target_tree.write(ROOT / spanish_path, encoding="utf-8", xml_declaration=True)
    print("WARNING: Spanish fallbacks copied from English for:")
    for tag, name in missing_es:
        print(f"  {tag}/{name}")

print(f"Merged upstream resources: EN +{en_added}, ES +{es_added}; Spanish fallbacks: {len(missing_es)}")
