"""Final release: retain evidence through legacy ANSWER and general summary nodes."""
import pathlib
import sys
import release_multi_product_merge_20260930 as guarded

release = guarded.release
release.ROOT = pathlib.Path('/opt/smart-assistant/releases/multi-product-answer-20260930')
release.release.ROOT = release.ROOT
release.release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.release.TAG = 'multi-product-answer-20260930'
release.release.TARGETS = {
    'product': ('smart-assistant-product-1.0.0-SNAPSHOT.jar',
                '3cba4f8f1fd26325f7d43ed76a34618f5ce917655d8680fa92465dcd30f685f0', 8084),
    'router': ('smart-assistant-router-scoped.jar',
               '55b3fae3715e89c54784c5710fac57ee0eeee52e0d41703b42a19e9fda8d4755', 8083),
}

if __name__ == '__main__':
    guarded.main(sys.argv[1] if len(sys.argv) == 2 else '')
