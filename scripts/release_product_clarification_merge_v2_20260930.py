"""Follow-up overlay using the real single-entity protocol version guard."""
import pathlib
import sys
import release_multi_product_merge_20260930 as guarded

release = guarded.release
release.ROOT = pathlib.Path('/opt/smart-assistant/releases/product-clarification-merge-v2-20260930')
release.release.ROOT = release.ROOT
release.release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.release.TAG = 'product-clarification-merge-v2-20260930'
release.release.TARGETS = {
    'router': ('smart-assistant-router-clarification-scoped.jar',
               'b185d2143659773071046d0c0485907c502e018df59806f3e75612d6be2a6919', 8083),
}

if __name__ == '__main__':
    guarded.main(sys.argv[1] if len(sys.argv) == 2 else '')
