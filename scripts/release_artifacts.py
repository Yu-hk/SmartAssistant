"""Read-only Docker JAR inventory and rollback verification; never deploys or reads Env.

Python 3.6+ for the current host. Snapshots are evidence, not deployment authorization.
"""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import sys


class ReleaseCheckError(ValueError):
    """A controlled diagnostic that never includes container environment values."""


def require(condition, message):
    if not condition:
        raise ReleaseCheckError(message)


def digest(path):
    path = pathlib.Path(path)
    require(path.is_file() and not path.is_symlink(), 'Artifact must be a regular non-symlink file')
    before = path.stat()
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    after = path.stat()
    require((before.st_size, before.st_mtime_ns, before.st_ino) ==
            (after.st_size, after.st_mtime_ns, after.st_ino), 'Artifact changed while hashing')
    require(after.st_size > 0, 'Artifact is empty')
    return result.hexdigest(), after.st_size


def inspect(name):
    require(re.fullmatch(r'smart-[a-z][a-z0-9-]{0,62}', name) is not None, 'Invalid service name')
    # Narrow format: never retrieve Config.Env, credentials or container log output.
    result = subprocess.run(['docker', 'inspect', '--format',
        '{{json .Image}}|{{json .State.Running}}|{{json .Mounts}}', name],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
    require(result.returncode == 0, 'Docker inspection failed for ' + name)
    fields = result.stdout.decode('utf-8').strip().split('|', 2)
    require(len(fields) == 3, 'Invalid Docker inspection result')
    info = dict(zip(('image', 'running', 'mounts'), map(json.loads, fields)))
    require(info['running'] is True, 'Service is not running: ' + name)
    mounted = subprocess.run(['docker', 'exec', name, 'sha256sum', '/app/app.jar'],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    require(mounted.returncode == 0, 'Mounted artifact hash failed for ' + name)
    parts = mounted.stdout.decode('utf-8').strip().split()
    require(len(parts) == 2 and parts[1] == '/app/app.jar', 'Invalid mounted hash result')
    info['containerSha256'] = parts[0]
    return info


def collect(names, inspector=inspect):
    require(names and len(names) == len(set(names)), 'Services must be nonempty and unique')
    services = []
    for name in sorted(names):
        require(re.fullmatch(r'smart-[a-z][a-z0-9-]{0,62}', name) is not None, 'Invalid service name')
        info = inspector(name)
        require(info['running'] is True, 'Service is not running: ' + name)
        image = info['image']
        # Docker-compatible runtimes may emit the full digest without the prefix.
        if isinstance(image, str) and re.fullmatch(r'[a-f0-9]{64}', image):
            image = 'sha256:' + image
        mounts = [m for m in info['mounts'] if m.get('Destination') == '/app/app.jar']
        require(len(mounts) == 1, 'Expected exactly one /app/app.jar mount: ' + name)
        mount = mounts[0]
        require(mount.get('Type') == 'bind' and mount.get('RW') is False,
                'Expected read-only JAR bind mount: ' + name)
        path = pathlib.Path(mount['Source'])
        require(path.is_absolute(), 'Artifact path must be absolute')
        sha, size = digest(path)
        # A renamed host file may differ from the inode still bind-mounted in Docker.
        require(info['containerSha256'] == sha, 'Mounted artifact sha256 differs from host: ' + name)
        services.append(dict(name=name, image=image, artifact=str(path),
                             destination='/app/app.jar', sha256=sha, size=size))
    snapshot = dict(schema=1, capturedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), services=services)
    validate(snapshot)
    return snapshot


def validate(snapshot):
    require(isinstance(snapshot, dict) and set(snapshot) == {'schema', 'capturedAt', 'services'}, 'Invalid snapshot fields')
    require(type(snapshot['schema']) is int and snapshot['schema'] == 1, 'Unsupported snapshot schema')
    require(isinstance(snapshot['capturedAt'], str) and bool(snapshot['capturedAt']), 'Missing snapshot time')
    require(isinstance(snapshot['services'], list) and bool(snapshot['services']), 'Missing services')
    names = set()
    for item in snapshot['services']:
        require(isinstance(item, dict) and set(item) == {'name', 'image', 'artifact', 'destination', 'sha256', 'size'}, 'Invalid service fields')
        require(isinstance(item['name'], str) and re.fullmatch(r'smart-[a-z][a-z0-9-]{0,62}', item['name']) is not None, 'Invalid service name')
        require(item['name'] not in names, 'Duplicate service')
        names.add(item['name'])
        require(isinstance(item['image'], str) and re.fullmatch(r'sha256:[a-f0-9]{64}', item['image']) is not None, 'Invalid image identity')
        require(isinstance(item['sha256'], str) and re.fullmatch(r'[a-f0-9]{64}', item['sha256']) is not None, 'Invalid artifact hash')
        require(isinstance(item['artifact'], str) and pathlib.Path(item['artifact']).is_absolute(), 'Invalid artifact path')
        require(item['destination'] == '/app/app.jar', 'Invalid artifact destination')
        require(type(item['size']) is int and item['size'] > 0, 'Invalid artifact size')


def verify(snapshot, inspector=inspect):
    validate(snapshot)
    actual = collect([s['name'] for s in snapshot['services']], inspector)
    expected_by_name = {s['name']: s for s in snapshot['services']}
    for item in actual['services']:
        expected = expected_by_name[item['name']]
        for key in ('image', 'artifact', 'destination', 'sha256', 'size'):
            require(expected[key] == item[key], 'Artifact verification failed: ' + item['name'] + ' / ' + key)
    return len(actual['services'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command')
    capture = commands.add_parser('snapshot')
    capture.add_argument('--service', action='append', required=True)
    capture.add_argument('--output', type=pathlib.Path, required=True)
    check = commands.add_parser('verify')
    check.add_argument('--snapshot', type=pathlib.Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'snapshot':
            snapshot = collect(args.service)
            # No overwrite: preserve the previous known-good rollback evidence.
            with args.output.open('x', encoding='utf-8') as stream:
                json.dump(snapshot, stream, ensure_ascii=False, indent=2)
            print('SNAPSHOT_SAVED services=' + str(len(snapshot['services'])))
        elif args.command == 'verify':
            with args.snapshot.open(encoding='utf-8') as stream:
                snapshot = json.load(stream)
            print('ARTIFACTS_VERIFIED services=' + str(verify(snapshot)))
        else:
            parser.error('A subcommand is required')
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError) as error:
        # Do not print Docker output or exception text that could contain sensitive data.
        detail = str(error) if isinstance(error, ReleaseCheckError) else type(error).__name__
        print('RELEASE_CHECK_FAILED: ' + detail, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
