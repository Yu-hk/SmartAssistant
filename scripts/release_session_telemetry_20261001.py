"""Consumer-only provenance overlay; retain original container and all unrelated live classes."""
import pathlib
import sys
import release_multi_product_merge_20260930 as guarded

release = guarded.release
release.ROOT = pathlib.Path('/opt/smart-assistant/releases/session-telemetry-20261001')
release.release.ROOT = release.ROOT
release.release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.release.TAG = 'session-telemetry-20261001'
release.release.TARGETS = {
    'consumer': ('smart-assistant-consumer-scoped.jar',
                 'c5c1607ca595ae452c382f83732dada0a63c64270e76a197c4c6f6f234e626f5', 8082),
}

if __name__ == '__main__':
    guarded.main(sys.argv[1] if len(sys.argv) == 2 else '')
