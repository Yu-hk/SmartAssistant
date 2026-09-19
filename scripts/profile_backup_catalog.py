"""Inspect backup object catalogs only. Never restores/executes SQL or emits row contents.

Custom dumps are listed with pg_restore --list via the configured PostgreSQL container.
Plain SQL files are scanned for DDL/COPY target names only. Archives and unknown formats
stay unverified. Output is coverage evidence, NOT a completed-erasure certificate.
"""
import hashlib
import json
import pathlib
import re
import subprocess

IDENTIFIER = r'[a-z_][a-z0-9_]*'
DDL = re.compile(r'^(?:CREATE TABLE(?: IF NOT EXISTS)?|COPY)\s+(?:public\.)?("?' + IDENTIFIER + r'"?)\b', re.I)
TOC = re.compile(r'^\d+;\s+\d+\s+\d+\s+TABLE(?: DATA)?\s+public\s+(' + IDENTIFIER + r')\s', re.I)

def catalog_names(text, custom):
    pattern = TOC if custom else DDL
    names = set(); in_copy = False
    for line in text.splitlines():
        line = line.strip()
        if in_copy:
            if line == r'\.': in_copy = False
            continue
        match = pattern.match(line)
        if match: names.add(match.group(1).strip('"'))
        if not custom and line.upper().startswith('COPY ') and re.search(r'\bFROM\s+stdin\s*;', line, re.I):
            in_copy = True
    return sorted(names)

def inspect_backups(root):
    if root.resolve() != pathlib.Path('/opt/smart-assistant/backups'):
        raise ValueError('Expected deployment backup directory')
    rows = []
    for path in sorted(root.rglob('*')):
        if not path.is_file() or path.is_symlink() or root not in path.resolve().parents:
            continue
        if path.suffix.lower() not in ('.dump', '.sql'):
            continue
        row = {'path': str(path.relative_to(root)), 'bytes': path.stat().st_size,
               'restored': False, 'rowContentsReported': False}
        if row['bytes'] > 64 * 1024 * 1024:
            row['status'] = 'BLOCKED_SIZE_LIMIT'; rows.append(row); continue
        row['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        if path.suffix.lower() == '.dump':
            with path.open('rb') as source:
                result = subprocess.run(['docker', 'exec', '-i', 'smart-postgres', 'pg_restore', '--list'],
                                        stdin=source, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
            if result.returncode:
                row['status'] = 'BLOCKED_UNREADABLE_CATALOG'; rows.append(row); continue
            names = catalog_names(result.stdout.decode('utf-8', errors='replace'), True)
        else:
            names = catalog_names(path.read_text(encoding='utf-8', errors='replace'), False)
        row['status'] = 'CATALOG_ONLY'
        row['tableCount'] = len(names)
        row['profileOrMemoryTables'] = [n for n in names if 'profile' in n or 'memory' in n or 'preference' in n]
        row['diagnosticTables'] = [n for n in names if 'audit' in n or 'routing' in n or 'workflow' in n]
        rows.append(row)
    return {'backups': rows, 'productionRestoreCertified': False,
            'unverified': ['compressed/unknown archives', 'external backup locations',
                           'row-level ownership and content', 'authoritative tombstone backup']}

if __name__ == '__main__':
    print(json.dumps(inspect_backups(pathlib.Path('/opt/smart-assistant/backups')), indent=2))
