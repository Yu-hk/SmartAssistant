"""Product-only follow-up release for declaration-aware recommendation wording."""
import pathlib
import sys

import release_product_rag_20260929 as product_release


release = product_release.release
release.ROOT = pathlib.Path('/opt/smart-assistant/releases/product-suitability-copy-20260929')
release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.TAG = 'product-suitability-copy-20260929'
release.TARGETS = {
    'product': ('smart-assistant-product-1.0.0-SNAPSHOT.jar',
                '2315ba8e8b164837aa3af60a405637192a9b8ffeb434cddc05522aa743c1d7e4',
                8084),
}


if __name__ == '__main__':
    try:
        product_release.main(sys.argv[1] if len(sys.argv) == 2 else '')
    except Exception as error:
        print('RELEASE_FAILED ' + type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
