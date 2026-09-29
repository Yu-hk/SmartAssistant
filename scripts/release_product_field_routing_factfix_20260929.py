"""Product-only release for source-gated deterministic weight answers."""

import pathlib
import sys

import release_product_field_routing_20260929 as release


release.ROOT = pathlib.Path('/opt/smart-assistant/releases/product-field-routing-factfix-20260929')
release.release.ROOT = release.ROOT
release.release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.release.TAG = 'product-field-routing-factfix-20260929'
release.release.TARGETS = {
    'product': ('smart-assistant-product-1.0.0-SNAPSHOT.jar',
                '37277762ba74bc174cd33c41ecf57f15a7e55509658df4d42cafc1fc5482ff18', 8084),
}


if __name__ == '__main__':
    try:
        release.main(sys.argv[1] if len(sys.argv) == 2 else '')
    except Exception as error:
        print('RELEASE_FAILED ' + type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
