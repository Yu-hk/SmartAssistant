#!/usr/bin/env python3
"""Fail when a named JaCoCo class falls below measured coverage floors.

Use a class-level floor for a newly refactored seam rather than claiming that a
small selected test suite measures the whole module. Missing/empty reports fail.
"""

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def coverage(report: Path, class_name: str, counter_type: str) -> tuple[int, int]:
    root = ET.parse(report).getroot()
    matches = [node for node in root.iter("class") if node.get("name") == class_name]
    if len(matches) != 1:
        raise ValueError(f"expected one JaCoCo class {class_name}, found {len(matches)}")
    counters = [node for node in matches[0].findall("counter") if node.get("type") == counter_type]
    if len(counters) != 1:
        raise ValueError(f"missing or duplicate {counter_type} counter for {class_name}")
    missed = int(counters[0].get("missed", "0"))
    covered = int(counters[0].get("covered", "0"))
    if missed + covered == 0:
        raise ValueError(f"empty {counter_type} counter for {class_name}")
    return covered, missed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("class_name", help="JaCoCo internal name, e.g. com/example/Foo")
    parser.add_argument("--instruction-minimum", type=float, required=True)
    parser.add_argument("--branch-minimum", type=float, required=True)
    args = parser.parse_args()
    for floor in (args.instruction_minimum, args.branch_minimum):
        if not 0 <= floor <= 1:
            parser.error("coverage minimum must be between 0 and 1")
    try:
        results = [(kind, minimum, *coverage(args.report, args.class_name, kind))
                   for kind, minimum in (("INSTRUCTION", args.instruction_minimum),
                                         ("BRANCH", args.branch_minimum))]
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"JaCoCo gate error: {error}", file=sys.stderr)
        return 2
    failed = False
    for kind, minimum, covered, missed in results:
        ratio = covered / (covered + missed)
        print(f"{args.class_name} {kind}: {ratio:.2%} ({covered}/{covered + missed}); minimum {minimum:.2%}")
        failed |= ratio < minimum
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
