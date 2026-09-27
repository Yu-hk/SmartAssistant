#!/usr/bin/env python3
"""Print path-only differences between two Spring Boot release JARs."""

import hashlib
import sys
import zipfile


def digests(path):
    with zipfile.ZipFile(path) as archive:
        return {
            name: hashlib.sha256(archive.read(name)).hexdigest()
            for name in archive.namelist()
            if not name.endswith("/") and
            (name.startswith("BOOT-INF/classes/") or name.startswith("BOOT-INF/lib/"))
        }


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: compare_release_jar_entries.py old.jar new.jar")
    old, new = digests(sys.argv[1]), digests(sys.argv[2])
    changes = [("removed" if key not in new else "added" if key not in old else "changed", key)
               for key in sorted(old.keys() | new.keys()) if old.get(key) != new.get(key)]
    print("changed_entries=" + str(len(changes)))
    for status, key in changes[:120]:
        print(status + " " + key)
    if len(changes) > 120:
        print("remaining_entries=" + str(len(changes) - 120))
