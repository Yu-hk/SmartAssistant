"""Product-only follow-up release for parenthesized catalog name matching.

Run alongside release_product_field_routing_20260929.py and its helper modules,
after a fresh release_artifacts.py snapshot of smart-product.
"""

import pathlib
import sys

import release_product_field_routing_20260929 as release


release.ROOT = pathlib.Path('/opt/smart-assistant/releases/product-field-routing-hotfix-20260929')
release.release.ROOT = release.ROOT
release.release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.release.TAG = 'product-field-routing-hotfix-20260929'
release.release.TARGETS = {
    'product': ('smart-assistant-product-1.0.0-SNAPSHOT.jar',
                '4da1ed66416fa6df9871578a86b805bca82e2249cbef18e96ff00fbc0418d9d0', 8084),
}


if __name__ == '__main__':
    try:
        release.main(sys.argv[1] if len(sys.argv) == 2 else '')
    except Exception as error:
        print('RELEASE_FAILED ' + type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
