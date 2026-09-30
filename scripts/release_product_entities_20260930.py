"""Guarded product/intake-only release; never replaces the live-only Router order baseline."""
import pathlib
import sys
import release_multi_product_merge_20260930 as guarded

release = guarded.release
release.ROOT = pathlib.Path('/opt/smart-assistant/releases/product-entities-20260930')
release.release.ROOT = release.ROOT
release.release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.release.TAG = 'product-entities-20260930'
release.release.TARGETS = {
    'product': ('smart-assistant-product-1.0.0-SNAPSHOT.jar',
                '71c802d7c77a71c2dac2202e4a7ffcd9c063ca8729b0094548301374c342d1bd', 8084),
    'data-intake': ('smart-assistant-data-intake-1.0.0-SNAPSHOT.jar',
                    'ea1e067b0feca0461ff27c414eea0b5d0cf5b1f405a76ba0987fcac0dfb7959b', 8092),
}

if __name__ == '__main__':
    guarded.main(sys.argv[1] if len(sys.argv) == 2 else '')
