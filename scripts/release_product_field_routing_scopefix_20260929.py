"""Product-only release for explicitly named catalog search scoping."""

import pathlib
import sys

import release_product_field_routing_20260929 as release


release.ROOT = pathlib.Path('/opt/smart-assistant/releases/product-field-routing-scopefix-20260929')
release.release.ROOT = release.ROOT
release.release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.release.TAG = 'product-field-routing-scopefix-20260929'
release.release.TARGETS = {
    'product': ('smart-assistant-product-1.0.0-SNAPSHOT.jar',
                'ea28b65d85172d83032cc15f87a2a5c429baa7ef3402179b7310188cef7f8537', 8084),
}


if __name__ == '__main__':
    try:
        release.main(sys.argv[1] if len(sys.argv) == 2 else '')
    except Exception as error:
        print('RELEASE_FAILED ' + type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
