"""Guarded one-method Router overlay. Preserve live-only order logic and all dependencies."""
import hashlib
import os
import pathlib
import subprocess
import sys
import zipfile
import json

ENTRY = 'BOOT-INF/classes/com/example/smartassistant/router/service/core/RouterService.class'
SOURCE = 'smart-assistant-router/src/main/java/com/example/smartassistant/router/service/core/RouterService.java'
BASE = '47d8743bb1c2481f0c80fb3c40e708f67e16c41d'

def main(repo, live, output, javac, asm_classpath):
    repo, live, output = map(pathlib.Path, (repo, live, output))
    if output.exists() or output.resolve() == live.resolve():
        raise RuntimeError('Output must be a new artifact, never a mounted JAR')
    built = repo / 'smart-assistant-router/target/smart-assistant-router-1.0.0-SNAPSHOT.jar'
    stage = repo / 'smart-assistant-router/target/entity-router-baseline'
    libs, classes = stage / 'libs', stage / 'classes'
    libs.mkdir(parents=True, exist_ok=True)
    classes.mkdir(exist_ok=True)
    source = stage / SOURCE
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_bytes(subprocess.check_output(['git', 'show', BASE + ':' + SOURCE], cwd=repo))
    with zipfile.ZipFile(built) as jar:
        for entry in jar.namelist():
            if entry.startswith('BOOT-INF/lib/') and entry.endswith('.jar'):
                (libs / pathlib.PurePosixPath(entry).name).write_bytes(jar.read(entry))
    cp = str(repo / 'smart-assistant-router/target/classes') + os.pathsep + str(libs / '*')
    subprocess.run([javac, '--release', '21', '-encoding', 'UTF-8', '-g', '-parameters',
                    '-cp', cp, '-d', str(classes), str(source)], check=True)
    baseline_class = classes / ENTRY.removeprefix('BOOT-INF/classes/')
    expected = baseline_class.read_bytes()
    digest = lambda value: hashlib.sha256(value).hexdigest()
    with zipfile.ZipFile(live) as original, zipfile.ZipFile(built) as replacement:
        live_class, built_class, patched_class = stage / 'live.class', stage / 'built.class', stage / 'method-patched.class'
        live_class.write_bytes(original.read(ENTRY))
        built_class.write_bytes(replacement.read(ENTRY))
        subprocess.run([javac, '-cp', asm_classpath, '-d', str(classes), str(repo / 'scripts/RouterEntityContextPatch.java')], check=True)
        java = str(pathlib.Path(javac).with_name('java.exe' if os.name == 'nt' else 'java'))
        subprocess.run([java, '-cp', str(classes) + os.pathsep + asm_classpath, 'RouterEntityContextPatch',
                        str(live_class), str(baseline_class), str(built_class), str(patched_class)], check=True)
        patched = patched_class.read_bytes()
        if patched == original.read(ENTRY): raise RuntimeError('No class change detected')
        with zipfile.ZipFile(output, 'x') as out:
            out.comment = original.comment
            for entry in original.infolist():
                out.writestr(entry, patched if entry.filename == ENTRY else original.read(entry.filename))
    with zipfile.ZipFile(live) as original, zipfile.ZipFile(output) as out:
        if original.namelist() != out.namelist(): raise RuntimeError('ZIP entries changed')
        changed = [name for name in original.namelist() if original.read(name) != out.read(name)]
        if changed != [ENTRY]: raise RuntimeError('Unexpected artifact changes')
    print(json.dumps({'changed': changed, 'baselineClassSha256': digest(expected), 'sha256': digest(output.read_bytes())}))

if __name__ == '__main__': main(*sys.argv[1:])
