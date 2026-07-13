import os, re, sys

ROOT = "D:/workspace/SmartAssistant"
import_re = re.compile(r'^\s*import\s+(static\s+)?([\w.]+)\s*;')
ident_re = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')
delete = '--delete' in sys.argv
total = 0

for dirpath, dirs, files in os.walk(ROOT):
    low = dirpath.lower().replace('\\', '/')
    if '/target/' in low or '.git' in low:
        continue
    for f in files:
        if not f.endswith('.java'):
            continue
        path = os.path.join(dirpath, f)
        try:
            src = open(path, encoding='utf-8').read()
        except Exception:
            continue
        lines = src.splitlines()
        drop = set()
        for i, l in enumerate(lines):
            m = import_re.match(l)
            if not m:
                continue
            simple = m.group(2).split('.')[-1]
            if simple == '*':
                continue
            body = '\n'.join(lines[:i] + lines[i + 1:])
            if simple not in set(ident_re.findall(body)):
                drop.add(i)
                total += 1
                rel = os.path.relpath(path, ROOT)
                print((f"DELETE {rel}:{i+1}: {l.strip()}" if delete else f"{rel}:{i+1}: {l.strip()}"))
        if delete and drop:
            kept = [l for i, l in enumerate(lines) if i not in drop]
            suffix = '\n' if src.endswith('\n') else ''
            open(path, 'w', encoding='utf-8').write('\n'.join(kept) + suffix)

print(f"UNUSED_IMPORTS={total}")
