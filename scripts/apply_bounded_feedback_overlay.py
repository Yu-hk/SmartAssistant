"""Apply only the reviewed Product classes; preserve every unrelated ZIP entry byte-for-byte."""
import argparse, copy, hashlib, json, zipfile
from pathlib import Path
from build_bounded_feedback_overlay import BASELINE, OLD, NEW, PREFIX

def apply(source, package, output):
    if hashlib.sha256(source.read_bytes()).hexdigest() != BASELINE: raise ValueError('Baseline drift')
    if output.exists(): raise FileExistsError('Fresh candidate required')
    guard = json.loads((package/'guard.json').read_text())
    old_names = {'BOOT-INF/classes/'+PREFIX+n for n in OLD}
    new_names = {'BOOT-INF/classes/'+PREFIX+n for n in NEW}
    if guard['baseline'] != BASELINE or set(guard['replace']) != old_names or set(guard['add']) != new_names:
        raise ValueError('Reviewed overlay scope drift')
    with zipfile.ZipFile(source) as baseline, zipfile.ZipFile(package/'replacement.zip') as patch:
        if len(baseline.namelist()) != len(set(baseline.namelist())): raise ValueError('Duplicate baseline entry')
        if set(patch.namelist()) != old_names | new_names or len(patch.namelist()) != len(old_names | new_names):
            raise ValueError('Overlay scope drift')
        if any(n in baseline.namelist() for n in new_names): raise ValueError('New class already exists')
        if any(hashlib.sha256(baseline.read(n)).hexdigest() != v for n,v in guard['replace'].items()):
            raise ValueError('Class baseline drift')
        with zipfile.ZipFile(output, 'x', zipfile.ZIP_DEFLATED) as candidate:
            for info in baseline.infolist(): candidate.writestr(copy.copy(info), patch.read(info.filename) if info.filename in old_names else baseline.read(info.filename))
            for name in sorted(new_names): candidate.writestr(name, patch.read(name))
        with zipfile.ZipFile(output) as candidate:
            if set(candidate.namelist()) != set(baseline.namelist()) | new_names: raise ValueError('Candidate entry drift')
            for name in baseline.namelist():
                if name not in old_names and baseline.read(name) != candidate.read(name): raise ValueError('Unrelated entry changed')
    return {'sha256':hashlib.sha256(output.read_bytes()).hexdigest(), 'replaced':len(old_names), 'added':len(new_names), 'unrelated_entries_preserved':True}

def main():
    p=argparse.ArgumentParser(); p.add_argument('--baseline',type=Path,required=True); p.add_argument('--package',type=Path,required=True); p.add_argument('--output',type=Path,required=True)
    a=p.parse_args(); print(json.dumps(apply(a.baseline,a.package,a.output)))
if __name__=='__main__': main()
