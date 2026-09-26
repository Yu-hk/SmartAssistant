"""Atomically publish the customer delete-wait UI on the known production host.

Upload the built frontend/dist to ROOT/dist first. Run preflight, then deploy.
The previous index is retained for immediate rollback; hashed assets coexist.
"""

import hashlib
import pathlib
import shutil
import sys
import urllib.request


ROOT = pathlib.Path('/opt/smart-assistant/releases/deferred-session-delete-20260926')
CANDIDATE = ROOT / 'dist'
LIVE = pathlib.Path('/opt/smart-assistant/frontend/dist')
INDEX_HASH = '5f46a19e0023972f4f34f2fd1eb5138e2210c708ffeb1b388dc6292a54390321'
MAIN_ASSET = 'index-Xf7QbA0D.js'
MAIN_HASH = '5af57d9c97976f6a8864f14af4888ab0e82024dcec3931c83516cdf2603ffb81'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_url(url):
    with urllib.request.urlopen(url, timeout=15) as response:
        return response.read()


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def preflight():
    check(LIVE.joinpath('index.html').is_file(), 'Live frontend index missing')
    check(digest(CANDIDATE.joinpath('index.html').read_bytes()) == INDEX_HASH,
          'Candidate index hash mismatch')
    check(digest(CANDIDATE.joinpath('assets', MAIN_ASSET).read_bytes()) == MAIN_HASH,
          'Candidate JavaScript hash mismatch')
    check(MAIN_ASSET.encode() in CANDIDATE.joinpath('index.html').read_bytes(),
          'Candidate index does not reference the expected asset')
    for asset in CANDIDATE.joinpath('assets').iterdir():
        if not asset.is_file():
            continue
        live_asset = LIVE / 'assets' / asset.name
        if live_asset.exists():
            check(digest(live_asset.read_bytes()) == digest(asset.read_bytes()),
                  'Existing asset differs: ' + asset.name)
    check(b'"status":"UP"' in read_url('https://xiaoyuai.cloud/healthz'),
          'Public health check failed')
    print('PREFLIGHT_OK', flush=True)


def deploy():
    preflight()
    old_index = LIVE / 'index.html'
    backup = ROOT / 'index-before.html'
    check(not backup.exists(), 'Release backup already exists')
    shutil.copy2(str(old_index), str(backup))
    try:
        for asset in CANDIDATE.joinpath('assets').iterdir():
            if asset.is_file():
                target = LIVE / 'assets' / asset.name
                if not target.exists():
                    shutil.copy2(str(asset), str(target))
        temporary = LIVE / 'index.deferred-session-delete.tmp'
        temporary.write_bytes(CANDIDATE.joinpath('index.html').read_bytes())
        temporary.replace(old_index)
        check(digest(read_url('https://xiaoyuai.cloud/assets/' + MAIN_ASSET)) == MAIN_HASH,
              'Public JavaScript hash mismatch')
        check(MAIN_ASSET.encode() in read_url('https://xiaoyuai.cloud/'),
              'Public index did not switch')
        check(b'"status":"UP"' in read_url('https://xiaoyuai.cloud/healthz'),
              'Public health check failed after cutover')
        (ROOT / 'deployment.txt').write_text('DEPLOYED ' + INDEX_HASH + '\n')
        print('DEPLOYED', flush=True)
    except Exception:
        temporary = LIVE / 'index.deferred-session-delete.rollback.tmp'
        temporary.write_bytes(backup.read_bytes())
        temporary.replace(old_index)
        print('INDEX_ROLLBACK_DONE', flush=True)
        raise


if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in ('preflight', 'deploy'):
        raise SystemExit('Expected preflight or deploy')
    if sys.argv[1] == 'preflight':
        preflight()
    else:
        deploy()
