"""Read-only archive-member inventory, not a restore or an erasure certificate.

Never extracts members, follows member links, executes SQL or reports member names/body.
TAR streams may be decompressed in memory to walk headers; ZIP is directory-only.
Oversize, malformed, encrypted, unsafe and changing inputs remain explicitly blocked.
Python 3.6+; CLI is restricted to the deployment backup root.
"""
import collections
import gzip
import json
import os
import pathlib
import struct
import tarfile
import time
import zipfile
import zlib

MAX_INPUT = 64 * 1024 * 1024
MAX_EXPANDED = 64 * 1024 * 1024
MAX_MEMBERS = 10000
MAX_ZIP_DIRECTORY = 4 * 1024 * 1024
MAX_SECONDS = 20
SUFFIXES = ('.tar', '.tar.gz', '.tgz', '.gz', '.zip')


class InventoryLimit(Exception):
    pass


class BoundedReader:
    def __init__(self, source, limit, deadline):
        self.source, self.limit, self.deadline = source, limit, deadline
        self.consumed = 0

    def read(self, size):
        if time.monotonic() > self.deadline or size < 0 or self.consumed + size > self.limit:
            raise InventoryLimit()
        data = self.source.read(size)
        self.consumed += len(data)
        return data


def classify(name):
    """Heuristic labels only; no name/body is emitted, no clean-content inference."""
    name = name.replace('\\', '/').lower()
    leaf = name.rsplit('/', 1)[-1]
    labels = []
    if leaf.endswith('-memory.md') or any(x in leaf for x in ('profile', 'preference')):
        labels.append('profileOrMemoryName')
    if leaf.endswith(('.sql', '.dump', '.backup', '.sql.gz')):
        labels.append('databaseBackup')
    if leaf.endswith(('.rdb', '.aof')) or '/appendonlydir/' in '/' + name:
        labels.append('redisPersistence')
    if leaf.endswith(('.log', '.log.gz')) or '/logs/' in '/' + name:
        labels.append('logs')
    if leaf in ('.env', 'application.yml', 'application.yaml', 'application.properties'):
        labels.append('configuration')
    if leaf.endswith(SUFFIXES):
        labels.append('nestedArchive')
    return labels


def unsafe_name(name):
    name = name.replace('\\', '/')
    return name.startswith('/') or ':' in name or '..' in name.split('/') or '\x00' in name


def check_zip_directory(source):
    # Bound central-directory allocation before ZipFile builds its in-memory list.
    source.seek(0, 2); size = source.tell()
    source.seek(max(0, size - 65557)); tail = source.read(65557)
    pos = tail.rfind(b'PK\x05\x06')
    if pos < 0 or len(tail) - pos < 22:
        raise zipfile.BadZipFile()
    fields = struct.unpack('<4s4H2LH', tail[pos:pos + 22])
    _, disk, directory_disk, disk_count, count, directory_size, offset, comment_size = fields
    if disk or directory_disk or disk_count != count or count == 65535 or offset == 0xffffffff:
        raise InventoryLimit()
    if count > MAX_MEMBERS or directory_size > MAX_ZIP_DIRECTORY or directory_size + offset > size:
        raise InventoryLimit()
    if pos + 22 + comment_size != len(tail):
        raise zipfile.BadZipFile()
    source.seek(0)


def inspect_archive(path):
    row = {'bytes': path.stat().st_size, 'status': 'BLOCKED_UNREADABLE', 'catalogComplete': False,
           'membersInspected': 0, 'declaredFileBytes': 0, 'categories': {},
           'unsafeMembers': 0, 'linkOrSpecialMembers': 0, 'encryptedMembers': 0,
           'extracted': False, 'memberNamesReported': False, 'rowContentsReported': False,
           'fullArchiveIntegrityVerified': False}
    before = path.stat()
    if path.is_symlink():
        row['status'] = 'BLOCKED_SYMLINK'; return row
    if row['bytes'] > MAX_INPUT:
        row['status'] = 'BLOCKED_INPUT_SIZE'; return row
    deadline = time.monotonic() + MAX_SECONDS
    counts = collections.Counter()

    def member(name, size, regular, link=False, encrypted=False):
        if time.monotonic() > deadline or row['membersInspected'] >= MAX_MEMBERS:
            raise InventoryLimit()
        row['membersInspected'] += 1
        row['unsafeMembers'] += int(unsafe_name(name))
        row['linkOrSpecialMembers'] += int(link)
        row['encryptedMembers'] += int(encrypted)
        if size < 0: raise InventoryLimit()
        if regular:
            row['declaredFileBytes'] += size
            counts.update(classify(name))
        if row['declaredFileBytes'] > MAX_EXPANDED: raise InventoryLimit()

    try:
        with path.open('rb') as source:
            if path.name.lower().endswith('.zip'):
                check_zip_directory(source)
                with zipfile.ZipFile(source) as archive:
                    for item in archive.infolist():
                        mode = (item.external_attr >> 16) & 0o170000
                        member(item.filename, item.file_size, not item.is_dir(),
                               mode not in (0, 0o100000, 0o040000), bool(item.flag_bits & 1))
            else:
                compressed = path.name.lower().endswith(('.gz', '.tgz'))
                stream = gzip.GzipFile(fileobj=source) if compressed else source
                try:
                    bounded = BoundedReader(stream, MAX_EXPANDED, deadline)
                    with tarfile.open(fileobj=bounded, mode='r|') as archive:
                        for item in archive:
                            member(item.name, item.size, item.isfile(), not (item.isfile() or item.isdir()))
                finally:
                    if compressed: stream.close()
        row['catalogComplete'] = True
        row['status'] = ('BLOCKED_UNSAFE_OR_ENCRYPTED' if
                         row['unsafeMembers'] or row['linkOrSpecialMembers'] or row['encryptedMembers']
                         else 'CATALOG_ONLY')
    except InventoryLimit:
        row['status'] = 'BLOCKED_INSPECTION_LIMIT'
    except (OSError, EOFError, ValueError, OverflowError, tarfile.TarError, zipfile.BadZipFile, zlib.error):
        row['status'] = 'BLOCKED_UNREADABLE_OR_UNSUPPORTED'
    finally:
        row['categories'] = dict(counts)
    after = path.stat()
    if (before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_ino, after.st_size, after.st_mtime_ns):
        row['status'] = 'BLOCKED_CHANGED_DURING_SCAN'; row['catalogComplete'] = False
    return row


def inventory(root):
    expected = pathlib.Path('/opt/smart-assistant/backups')
    if root != expected or root.resolve() != expected or not root.is_dir():
        raise ValueError('Expected existing deployment backup directory')
    rows = []
    for directory, dirs, files in os.walk(str(root), followlinks=False):
        dirs[:] = sorted(d for d in dirs if not pathlib.Path(directory, d).is_symlink())
        for filename in sorted(files):
            path = pathlib.Path(directory, filename)
            if not filename.lower().endswith(SUFFIXES): continue
            if path.is_symlink() or root not in path.resolve().parents:
                rows.append({'path': str(path.relative_to(root)), 'status': 'BLOCKED_SYMLINK'})
                continue
            if not path.is_file(): continue
            try:
                row = inspect_archive(path)
            except OSError:
                # A disappearing/unreadable archive must not silently disappear from coverage.
                row = {'status': 'BLOCKED_UNREADABLE_OR_CHANGED', 'catalogComplete': False}
            row['path'] = str(path.relative_to(root)); rows.append(row)
    return {'scope': 'archive-member-metadata-only', 'archives': rows,
            'productionRestoreCertified': False, 'erasureComplete': False,
            'unverified': ['member contents and ownership', 'nested archives', 'non-archive files',
                           'external backup locations', 'symlink directories', 'authoritative control backup']}


if __name__ == '__main__':
    print(json.dumps(inventory(pathlib.Path('/opt/smart-assistant/backups')), indent=2))
