"""Router ResultMerger-only overlay; preserve all live-only order classes and libraries."""
import pathlib
import sys
import release_multi_product_merge_20260930 as guarded

release = guarded.release
release.ROOT = pathlib.Path('/opt/smart-assistant/releases/product-clarification-merge-20260930')
release.release.ROOT = release.ROOT
release.release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.release.TAG = 'product-clarification-merge-20260930'
release.release.TARGETS = {
    'router': ('smart-assistant-router-clarification-scoped.jar',
               'ab54275d57fddb3cb83e604696d84635abbe823af9c8d3c6a2b9320b7fe70338', 8083),
}

if __name__ == '__main__':
    guarded.main(sys.argv[1] if len(sys.argv) == 2 else '')
