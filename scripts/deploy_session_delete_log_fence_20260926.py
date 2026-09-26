"""Consumer cutover: persist conversation turns before release/delete can race."""

import pathlib
import sys

import deploy_cross_browser_session_20260925 as cutover


cutover.release.ROOT = pathlib.Path('/opt/smart-assistant/releases/session-delete-log-fence-20260926')
cutover.release.SNAPSHOT = cutover.release.ROOT / 'before-artifacts.json'
cutover.release.TAG = 'session-delete-log-fence-20260926'
cutover.release.TARGETS = {
    'consumer': ('smart-assistant-consumer-1.0.0-SNAPSHOT.jar',
                 '67f8460baab356ea536953e27040204df96b2c46f9db29fa37fe533a384da6e4', 8082),
}


if __name__ == '__main__':
    try:
        cutover.main(sys.argv[1] if len(sys.argv) == 2 else '')
    except Exception as error:
        print('RELEASE_FAILED ' + type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
