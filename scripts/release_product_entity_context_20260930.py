"""Follow-up release: Product history adapter plus a one-method, live-order-preserving Router overlay."""
import pathlib
import sys
import release_multi_product_merge_20260930 as guarded

release = guarded.release
release.ROOT = pathlib.Path('/opt/smart-assistant/releases/product-entity-context-20260930')
release.release.ROOT = release.ROOT
release.release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.release.TAG = 'product-entity-context-20260930'
release.release.TARGETS = {
    'product': ('smart-assistant-product-1.0.0-SNAPSHOT.jar',
                'f495487fe05cc72f9aed19d246b85f241f465ebb5dc5cf63885cb9cd4e201109', 8084),
    'router': ('smart-assistant-router-entity-scoped.jar',
               '02362a48674ae66cc41ee9f799abd88db1678e9be9816c14b52e753068fb5a8e', 8083),
}
if __name__ == '__main__': guarded.main(sys.argv[1] if len(sys.argv) == 2 else '')
