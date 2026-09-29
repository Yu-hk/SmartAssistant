"""Release the verified product-search recall fix with the product-only rollout."""

import pathlib
import sys

import release_product_rag_20260929 as product_release


release = product_release.release
release.ROOT = pathlib.Path('/opt/smart-assistant/releases/product-search-recall-20260929')
release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.TAG = 'product-search-recall-20260929'
release.TARGETS = {
    'product': ('smart-assistant-product-1.0.0-SNAPSHOT.jar',
                '6fa586283c2f3f0c1e185a132681d87f476062e3962a1ba7264144d2d82c64d2',
                8084),
}


if __name__ == '__main__':
    try:
        product_release.main(sys.argv[1] if len(sys.argv) == 2 else '')
    except Exception as error:
        print('RELEASE_FAILED ' + type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
