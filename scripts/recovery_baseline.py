"""Fail-closed allowlist for the explicitly supported recovery inputs.

Verification only: never migrates, restores, connects to a database or starts traffic.
The binary backup digest must come from approved inventory, not from the file itself.
"""
import argparse
import hashlib
import json
import pathlib
import stat

REPOSITORY_BASELINE = 'consolidated-20260920'
PRODUCTION_BASELINE = 'production-backup-20260920'
SQL_SHA256 = {
    'docs/database/schema.sql': '1e5243a35285394136ff7f7a1f0b5a21281ba3526bf2659e046ba01b3e755939',
    'docs/database/seed_data.sql': '0c9d401a4919362f9fdc7e2171708b298ee8940e5ebaf31087fb4cd22abda757',
}
BACKUP_SHA256 = 'bb95f8a6020707eaa8fe447427318d06c704107a5faae99e36c99a1030533571'


def validate_name(name, expected):
    if name != expected:
        raise ValueError('Unsupported recovery baseline; explicit review required')


def verify_repository(repo, baseline=REPOSITORY_BASELINE):
    validate_name(baseline, REPOSITORY_BASELINE)
    for name, expected in SQL_SHA256.items():
        # Git may check out CRLF on Windows; no other normalization is allowed.
        raw = (repo / name).read_bytes().replace(b'\r\n', b'\n')
        if hashlib.sha256(raw).hexdigest() != expected:
            raise ValueError('Supported schema/seed baseline changed; review required')
    return baseline


def verify_backup(path, baseline):
    validate_name(baseline, PRODUCTION_BASELINE)
    if path.is_symlink() or not stat.S_ISREG(path.lstat().st_mode):
        raise ValueError('Regular backup file required')
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        if stream.read(5) != b'PGDMP':
            raise ValueError('PostgreSQL custom-format backup required')
        stream.seek(0)
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    if digest.hexdigest() != BACKUP_SHA256:
        raise ValueError('Backup does not match the approved inventory')
    return baseline


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', required=True)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--repo', type=pathlib.Path)
    group.add_argument('--backup', type=pathlib.Path)
    args = parser.parse_args()
    if args.repo: verify_repository(args.repo, args.baseline)
    else: verify_backup(args.backup, args.baseline)
    print(json.dumps({'baseline': args.baseline, 'inputVerified': True,
                      'databaseModified': False, 'trafficStarted': False}))
