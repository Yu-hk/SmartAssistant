"""Consumer-only hotfix after independent-session migration and verification."""

import pathlib
import sys

import deploy_cross_browser_session_20260925 as cutover


cutover.release.ROOT = pathlib.Path('/opt/smart-assistant/releases/session-request-fence-20260926')
cutover.release.SNAPSHOT = cutover.release.ROOT / 'before-artifacts.json'
cutover.release.TAG = 'session-request-fence-20260926'
cutover.release.TARGETS = {
    'consumer': ('smart-assistant-consumer-1.0.0-SNAPSHOT.jar',
                 'dda97c96177828383037d12abf14576403dbec49386399f8e5903b2d66b63130', 8082),
}


if __name__ == '__main__':
    try:
        cutover.main(sys.argv[1] if len(sys.argv) == 2 else '')
    except Exception as error:
        print('RELEASE_FAILED ' + type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
