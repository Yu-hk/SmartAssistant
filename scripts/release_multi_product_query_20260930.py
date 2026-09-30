"""Product-only, hash-checked release of bounded multi-product read queries."""
import pathlib
import sys
import release_product_field_routing_20260929 as release

release.ROOT = pathlib.Path('/opt/smart-assistant/releases/multi-product-query-20260930')
release.release.ROOT = release.ROOT
release.release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.release.TAG = 'multi-product-query-20260930'
release.release.TARGETS = {
    'product': ('smart-assistant-product-1.0.0-SNAPSHOT.jar',
                'c4c6a4d362a6e7e99491f1c20e1f9b04c5dfebe47a1ed1ea54cb8f864bd45541', 8084),
}

if __name__ == '__main__':
    try:
        release.main(sys.argv[1] if len(sys.argv) == 2 else '')
    except Exception as error:
        print('RELEASE_FAILED ' + type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
