"""Read-only physical storage metadata for current/retired AI containers (Python 3.6+).

Only Docker ps/inspect and filesystem directory/stat operations. No container start,
exec, mount, export, file-body read, deletion, or effective runtime-config assertion.
Image layers may contain shadowed/deleted historical files: not a merged-view scan.
"""
import json
import os
import pathlib
import re
import stat
import subprocess
import time

NAME = re.compile(r'^smart-(consumer|router|product|order)(?:-[a-zA-Z0-9_-]+)?$')
ALLOWED_ROOTS = ('/var/lib/containers/storage/overlay', '/var/lib/containers/storage/volumes',
                 '/var/lib/docker/overlay2', '/var/lib/docker/volumes', '/opt/smart-assistant')
MAX_ENTRIES = 100000
MAX_SECONDS = 20


def run(*args):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
    if result.returncode: raise RuntimeError('Container metadata unavailable')
    return result.stdout.decode('utf-8')


def allowed(path):
    """Require an exact non-symlink descendant, never a broad storage/workspace root."""
    try:
        return path.is_absolute() and path.resolve() == path and any(
            pathlib.Path(root) in path.parents for root in ALLOWED_ROOTS)
    except (OSError, RuntimeError): return False


def scan_tree(path):
    row = {'path': str(path), 'status': 'BLOCKED_PATH', 'entries': 0, 'regularFiles': 0,
           'memoryFileCandidates': 0, 'memoryLinkCandidates': 0, 'symlinksNotFollowed': 0,
           'specialFilesNotRead': 0, 'nestedDevicesNotScanned': 0, 'errors': 0,
           'fileBodiesRead': False, 'memberNamesReported': False}
    if not allowed(path): return row
    try:
        info = path.lstat()
        if stat.S_ISREG(info.st_mode):
            row.update(status='REGULAR_FILE_METADATA_ONLY', entries=1, regularFiles=1,
                       memoryFileCandidates=int(path.name.endswith('-memory.md')))
            return row
        if not stat.S_ISDIR(info.st_mode): return row
        device = info.st_dev; pending = [path]; deadline = time.monotonic() + MAX_SECONDS
        while pending:
            current = pending.pop()
            if time.monotonic() > deadline:
                row['status'] = 'BLOCKED_LIMIT'; return row
            # Do not follow a directory changed into a symlink during enumeration.
            if current.is_symlink() or current.resolve() != current:
                row['errors'] += 1; continue
            try:
                with os.scandir(str(current)) as entries:
                    for entry in entries:
                        if row['entries'] >= MAX_ENTRIES or time.monotonic() > deadline:
                            row['status'] = 'BLOCKED_LIMIT'; return row
                        row['entries'] += 1
                        try:
                            # DirEntry.stat can expose st_dev=0 on Windows; use a fresh lstat.
                            item = os.lstat(entry.path)
                            if stat.S_ISLNK(item.st_mode):
                                row['symlinksNotFollowed'] += 1
                                row['memoryLinkCandidates'] += int(entry.name.endswith('-memory.md'))
                            elif item.st_dev != device:
                                row['nestedDevicesNotScanned'] += 1
                            elif stat.S_ISDIR(item.st_mode): pending.append(pathlib.Path(entry.path))
                            elif stat.S_ISREG(item.st_mode):
                                row['regularFiles'] += 1
                                row['memoryFileCandidates'] += int(entry.name.endswith('-memory.md'))
                            else: row['specialFilesNotRead'] += 1
                        except OSError: row['errors'] += 1
            except OSError: row['errors'] += 1
        row['status'] = 'METADATA_ENUMERATED' if not row['errors'] else 'BLOCKED_IO'
    except (OSError, RuntimeError): row['status'] = 'BLOCKED_UNAVAILABLE'
    return row


def storage_plan(meta):
    driver = meta.get('GraphDriver') or {}; data = driver.get('Data') or {}
    paths = []
    if driver.get('Name') in ('overlay', 'overlay2'):
        if data.get('UpperDir'): paths.append(('writable-layer', data['UpperDir']))
        paths.extend(('image-layer', p) for p in data.get('LowerDir', '').split(':') if p)
    for mount in meta.get('Mounts', []):
        if mount.get('Type') in ('bind', 'volume') and mount.get('Source'):
            paths.append(('mount', mount['Source']))
    return paths


def stamp(meta):
    return (meta['Id'], meta['State']['Running'], meta['State'].get('StartedAt'),
            storage_plan(meta))


def inventory():
    def names(): return sorted(n for n in run('docker','ps','-a','--format','{{.Names}}').splitlines() if NAME.fullmatch(n))
    initial_names = names(); roots = {}; containers = []
    for name in initial_names:
        meta = json.loads(run('docker','inspect',name))[0]; before = stamp(meta)
        refs = []
        for role, source in storage_plan(meta):
            if source not in roots: roots[source] = scan_tree(pathlib.Path(source))
            refs.append({'role': role, 'source': source})
        after = json.loads(run('docker','inspect',meta['Id']))[0]
        containers.append({'name': name, 'id': meta['Id'], 'image': meta.get('Image'),
                           'running': meta['State']['Running'], 'metadataStable': before == stamp(after),
                           'driver': (meta.get('GraphDriver') or {}).get('Name'), 'storage': refs,
                           'layerMetadataAvailable': any(r['role']=='writable-layer' for r in refs),
                           'unhandledMountTypes': sorted(set(m.get('Type','unknown') for m in meta.get('Mounts',[])
                                                            if m.get('Type') not in ('bind','volume'))),
                           'effectiveRuntimeConfigurationVerified': False})
    return {'scope': 'known-container-physical-file-metadata', 'containers': containers,
            'storageRoots': list(roots.values()), 'containerSetStable': initial_names == names(),
            'erasureComplete': False, 'unverified': ['file contents and ownership', 'unfollowed links and nested devices',
             'remote or unlisted storage', 'removed/unlisted container layers', 'runtime configuration',
             'live filesystem mutations (not an atomic snapshot)', 'old binary activation safety']}


if __name__ == '__main__': print(json.dumps(inventory(), indent=2))
