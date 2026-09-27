#!/usr/bin/env python3
"""List class/resource changes inside Spring Boot's nested project JARs."""

import hashlib
import io
import sys
import zipfile


def nested_entries(outer_path, name):
    with zipfile.ZipFile(outer_path) as outer:
        with zipfile.ZipFile(io.BytesIO(outer.read(name))) as inner:
            return {
                entry: hashlib.sha256(inner.read(entry)).hexdigest()
                for entry in inner.namelist()
                if not entry.endswith("/") and not entry.startswith("META-INF/")
            }


def main(old_path, new_path):
    with zipfile.ZipFile(old_path) as old, zipfile.ZipFile(new_path) as new:
        libraries = sorted(
            name for name in set(old.namelist()) & set(new.namelist())
            if name.startswith("BOOT-INF/lib/smart-assistant-") and name.endswith(".jar")
            and old.getinfo(name).CRC != new.getinfo(name).CRC
        )
    for library in libraries:
        old_entries = nested_entries(old_path, library)
        new_entries = nested_entries(new_path, library)
        changed = [
            entry for entry in sorted(old_entries.keys() | new_entries.keys())
            if old_entries.get(entry) != new_entries.get(entry)
        ]
        print("{}: {} changed entries".format(library, len(changed)))
        for entry in changed:
            status = "added" if entry not in old_entries else "removed" if entry not in new_entries else "changed"
            print("  {} {}".format(status, entry))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: compare_nested_release_jars.py old.jar new.jar")
    main(sys.argv[1], sys.argv[2])
