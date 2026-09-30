"""Append the tested reference guard and replace only the byte-identical planner baseline class."""
import hashlib
import pathlib
import sys
import zipfile
import json

TARGET = 'BOOT-INF/classes/com/example/smartassistant/router/service/taskanalysis/TaskAnalysisService.class'
NEW = 'BOOT-INF/classes/com/example/smartassistant/router/service/taskanalysis/ProductEntityReferencePlanGuard.class'
EXPECTED = 'd7d177280babcccb41886886f8b7879de8af9936459f988495b6fe28e759d74b'
def main(live, built, output):
    output = pathlib.Path(output)
    if output.exists() or output.resolve() in (pathlib.Path(live).resolve(), pathlib.Path(built).resolve()):
        raise RuntimeError('Output must be a new artifact')
    digest = lambda data: hashlib.sha256(data).hexdigest()
    with zipfile.ZipFile(live) as old, zipfile.ZipFile(built) as replacement:
        if digest(old.read(TARGET)) != EXPECTED or NEW in old.namelist():
            raise RuntimeError('Unexpected live planner baseline; do not patch')
        if old.read(TARGET) == replacement.read(TARGET): raise RuntimeError('No planner class change')
        with zipfile.ZipFile(output, 'x') as out:
            out.comment = old.comment
            for entry in old.infolist():
                out.writestr(entry, replacement.read(TARGET) if entry.filename == TARGET else old.read(entry.filename))
            out.writestr(replacement.getinfo(NEW), replacement.read(NEW))
    with zipfile.ZipFile(live) as old, zipfile.ZipFile(output) as out:
        if out.namelist() != old.namelist() + [NEW]: raise RuntimeError('Unexpected added entries')
        changed = [name for name in old.namelist() if old.read(name) != out.read(name)]
        if changed != [TARGET]: raise RuntimeError('Unexpected changes')
    print(json.dumps({'changed': changed, 'added': [NEW], 'sha256': digest(output.read_bytes())}))
if __name__ == '__main__': main(*sys.argv[1:])
