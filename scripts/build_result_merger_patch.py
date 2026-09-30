"""Build-scoped JAR overlay with strict original-class guards; never edit a mounted JAR.

baseline: compare clean main's ResultMerger classes with live artifact before any overlay.
patch: preserve every live ZIP entry except the two tested ResultMerger class files.
"""
import hashlib
import json
import pathlib
import subprocess
import sys
import zipfile

PREFIX = 'BOOT-INF/classes/com/example/smartassistant/router/service/core/'
CLASSES = ('ResultMerger.class', 'ResultMerger$FactValue.class')

def digest(value):
    return hashlib.sha256(value).hexdigest()

def baseline(repo, javac):
    repo = pathlib.Path(repo).resolve()
    root = repo / 'smart-assistant-router/target/merger-baseline'
    jars = root / 'libs'
    jars.mkdir(exist_ok=True)
    with zipfile.ZipFile(repo / 'smart-assistant-router/target/smart-assistant-router-1.0.0-SNAPSHOT.jar') as jar:
        for name in jar.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                (jars / pathlib.PurePosixPath(name).name).write_bytes(jar.read(name))
    source = root / 'source/smart-assistant-router/src/main/java/com/example/smartassistant/router/service/core/ResultMerger.java'
    classes = root / 'classes'
    classes.mkdir(exist_ok=True)
    import os
    cp = str(repo / 'smart-assistant-router/target/classes') + os.pathsep + str(jars / '*')
    subprocess.run([javac, '--release', '21', '-encoding', 'UTF-8', '-g', '-parameters',
                    '-cp', cp, '-d', str(classes), str(source)], check=True)
    expected = {PREFIX + name: digest((classes / 'com/example/smartassistant/router/service/core' / name).read_bytes())
                for name in CLASSES}
    (root / 'baseline-hashes.json').write_text(json.dumps(expected), encoding='utf-8')
    print(json.dumps(expected))

def patch(live_path, built_path, guard_path, output_path):
    live_path, built_path, output_path = map(pathlib.Path, (live_path, built_path, output_path))
    if output_path.exists() or output_path.resolve() in (live_path.resolve(), built_path.resolve()):
        raise RuntimeError('Output must be a new, separate artifact')
    expected = json.loads(pathlib.Path(guard_path).read_text())
    if set(expected) != {PREFIX + name for name in CLASSES}:
        raise RuntimeError('Unexpected class guard set')
    with zipfile.ZipFile(live_path) as live, zipfile.ZipFile(built_path) as built:
        for name, sha in expected.items():
            if digest(live.read(name)) != sha:
                raise RuntimeError('Live baseline class differs: ' + name)
        with zipfile.ZipFile(output_path, 'x') as out:
            out.comment = live.comment
            for entry in live.infolist():
                out.writestr(entry, built.read(entry.filename) if entry.filename in expected else live.read(entry.filename))
    with zipfile.ZipFile(live_path) as live, zipfile.ZipFile(output_path) as out:
        if live.namelist() != out.namelist():
            raise RuntimeError('ZIP entries changed')
        changed = [name for name in live.namelist() if digest(live.read(name)) != digest(out.read(name))]
        if not changed or not set(changed).issubset(expected):
            raise RuntimeError('Unexpected artifact changes')
    print(json.dumps({'changed': changed, 'sha256': digest(output_path.read_bytes())}))

if __name__ == '__main__':
    if sys.argv[1] == 'baseline':
        baseline(*sys.argv[2:])
    elif sys.argv[1] == 'patch':
        patch(*sys.argv[2:])
    else:
        raise SystemExit('Expected baseline or patch')
