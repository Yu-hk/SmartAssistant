"""One-service coverage/parser release; retain the prior Product container."""
import pathlib
import sys
import release_multi_product_merge_20260930 as guarded

release = guarded.release
release.ROOT = pathlib.Path('/opt/smart-assistant/releases/product-coverage-20260930')
release.release.ROOT = release.ROOT
release.release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.release.TAG = 'product-coverage-20260930'
release.release.TARGETS = {
    'product': ('smart-assistant-product-1.0.0-SNAPSHOT.jar',
                '1f2dc8230e7a7e2f3d112d8c316729a5fe1dcd2e0784e385baf7079d181a28c2', 8084),
}

if __name__ == '__main__':
    guarded.main(sys.argv[1] if len(sys.argv) == 2 else '')
