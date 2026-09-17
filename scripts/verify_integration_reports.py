"""Fail if a named integration suite is absent, skipped, empty or unsuccessful."""
import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def verify(path, minimum=1):
    suite = ET.parse(path).getroot()
    if int(suite.get('tests', '0')) < minimum:
        raise ValueError(f'{path}: expected at least {minimum} tests')
    for name in ('failures', 'errors', 'skipped'):
        if int(suite.get(name, '0')) != 0:
            raise ValueError(f'{path}: {name} must be zero')
    if any(next(suite.iter(tag), None) is not None for tag in ('skipped', 'failure', 'error')):
        raise ValueError(f'{path}: unsuccessful testcase')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    parser.add_argument('--minimum', type=int, default=1)
    args = parser.parse_args()
    try:
        verify(args.report, args.minimum)
    except (OSError, ValueError, ET.ParseError) as error:
        print(error, file=sys.stderr)
        return 1
    print('Integration suite executed without skips: ' + args.report.name)
    return 0


if __name__ == '__main__':
    sys.exit(main())
