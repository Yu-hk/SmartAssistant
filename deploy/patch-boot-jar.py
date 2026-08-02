#!/usr/bin/env python3
"""Atomically replace selected entries in a Spring Boot executable JAR."""

import os
import stat
import sys
import zipfile
from pathlib import Path


def parse_replacements(values):
    replacements = {}
    for value in values:
        if "=" not in value:
            raise ValueError(f"replacement must be JAR_ENTRY=LOCAL_FILE: {value}")
        entry, source = value.split("=", 1)
        source_path = Path(source)
        if not entry.startswith("BOOT-INF/classes/") or ".." in Path(entry).parts:
            raise ValueError(f"unsafe or unsupported JAR entry: {entry}")
        if not source_path.is_file():
            raise FileNotFoundError(source_path)
        replacements[entry] = source_path
    if not replacements:
        raise ValueError("at least one replacement is required")
    return replacements


def patch(jar_path, replacements):
    if not jar_path.is_file():
        raise FileNotFoundError(jar_path)

    original_mode = stat.S_IMODE(jar_path.stat().st_mode)
    temporary_path = jar_path.with_suffix(jar_path.suffix + ".patching")
    replaced = set()

    try:
        with zipfile.ZipFile(jar_path, "r") as source, zipfile.ZipFile(temporary_path, "w") as target:
            for info in source.infolist():
                if info.filename in replacements:
                    target.writestr(info, replacements[info.filename].read_bytes())
                    replaced.add(info.filename)
                else:
                    target.writestr(info, source.read(info.filename))

        missing = set(replacements) - replaced
        if missing:
            raise KeyError(f"entries not found in JAR: {sorted(missing)}")

        with zipfile.ZipFile(temporary_path, "r") as verification:
            bad_entry = verification.testzip()
            if bad_entry is not None:
                raise zipfile.BadZipFile(f"CRC check failed: {bad_entry}")

        os.chmod(temporary_path, original_mode)
        os.replace(temporary_path, jar_path)
    finally:
        try:
            temporary_path.unlink()
        except FileNotFoundError:
            pass


def main() -> int:
    if len(sys.argv) < 3:
        print(f"usage: {sys.argv[0]} JAR ENTRY=FILE [ENTRY=FILE ...]", file=sys.stderr)
        return 2
    patch(Path(sys.argv[1]), parse_replacements(sys.argv[2:]))
    print(f"patched {sys.argv[1]} ({len(sys.argv) - 2} entries)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
