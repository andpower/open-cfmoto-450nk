#!/usr/bin/env python3
"""Wrap Kotlin UI string literals with Context.uiText().

This deliberately targets only UI sinks. Protocol strings, logs, persisted values and network
requests must remain stable English/internal values.
"""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/java/dev/zanderp/opencfmoto"


def string_end(source: str, start: int) -> int | None:
    """Return the offset immediately after a Kotlin quoted string."""
    if not source.startswith('"', start) or source.startswith('"""', start):
        return None
    i = start + 1
    interpolation_depth = 0
    while i < len(source):
        ch = source[i]
        if ch == "\\":
            i += 2
            continue
        if ch == "$" and i + 1 < len(source) and source[i + 1] == "{":
            interpolation_depth += 1
            i += 2
            continue
        if interpolation_depth:
            if ch == '"':
                nested = string_end(source, i)
                if nested is None:
                    return None
                i = nested
                continue
            if ch == "{":
                interpolation_depth += 1
            elif ch == "}":
                interpolation_depth -= 1
            i += 1
            continue
        if ch == '"':
            return i + 1
        i += 1
    return None


def context_call(path: Path) -> str:
    name = path.name
    if name == "GpxDashUi.kt":
        return "context.uiText"
    if name == "DependencyPrompt.kt":
        return "activity.uiText"
    if name == "WifiGate.kt":
        return "activity.uiText"
    if name == "VideoPipeline.kt":
        return "pres.context.uiText"
    return "uiText"


def wrap_at(source: str, start: int, call: str) -> tuple[str, int, bool]:
    while start < len(source) and source[start].isspace():
        start += 1
    if not source.startswith('"', start):
        return source, start, False
    end = string_end(source, start)
    if end is None:
        return source, start, False
    source = source[:start] + f"{call}(" + source[start:end] + ")" + source[end:]
    return source, end + len(call) + 2, True


def transform(path: Path) -> int:
    source = path.read_text()
    call = context_call(path)
    changed = 0

    # Dialog button/title/message methods and SetupActivity's toast helper.
    needles = [
        ".setTitle(",
        ".setMessage(",
        ".setPositiveButton(",
        ".setNegativeButton(",
        ".setNeutralButton(",
        "toast(",
    ]
    for needle in needles:
        cursor = 0
        while True:
            pos = source.find(needle, cursor)
            if pos < 0:
                break
            arg = pos + len(needle)
            if source.startswith(f"{call}(", arg):
                cursor = arg + len(call) + 1
                continue
            source, cursor, did = wrap_at(source, arg, call)
            changed += int(did)
            if not did:
                cursor = arg + 1

    # Toast.makeText(context, <message>, duration)
    cursor = 0
    while True:
        pos = source.find("Toast.makeText(", cursor)
        if pos < 0:
            break
        arg = pos + len("Toast.makeText(")
        depth = 0
        comma = -1
        i = arg
        while i < len(source):
            if source[i] == '"':
                end = string_end(source, i)
                if end is None:
                    break
                i = end
                continue
            if source[i] in "([{":
                depth += 1
            elif source[i] in ")]}":
                depth -= 1
            elif source[i] == "," and depth == 0:
                comma = i
                break
            i += 1
        if comma < 0:
            cursor = arg + 1
            continue
        if source.startswith(f"{call}(", comma + 1):
            cursor = comma + len(call) + 2
            continue
        source, cursor, did = wrap_at(source, comma + 1, call)
        changed += int(did)
        if not did:
            cursor = comma + 1

    # TextView.text = "..."
    cursor = 0
    while True:
        pos = source.find(".text =", cursor)
        if pos < 0:
            break
        arg = pos + len(".text =")
        if source.startswith(f"{call}(", arg):
            cursor = arg + len(call) + 1
            continue
        source, cursor, did = wrap_at(source, arg, call)
        changed += int(did)
        if not did:
            cursor = arg + 1

    if changed:
        path.write_text(source)
    return changed


def main() -> None:
    total = 0
    files = 0
    for path in sorted(ROOT.glob("*.kt")):
        count = transform(path)
        if count:
            print(f"{path.name}: {count}")
            total += count
            files += 1
    print(f"Wrapped {total} UI strings in {files} Kotlin files.")


if __name__ == "__main__":
    main()
