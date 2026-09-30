"""Router-only follow-up: guarded planner reference repair, preserving the current live order baseline."""
import pathlib
import sys
import release_multi_product_merge_20260930 as guarded

release = guarded.release
release.ROOT = pathlib.Path('/opt/smart-assistant/releases/product-entity-plan-20260930')
release.release.ROOT = release.ROOT
release.release.SNAPSHOT = release.ROOT / 'before-artifacts.json'
release.release.TAG = 'product-entity-plan-20260930'
release.release.TARGETS = {
    'router': ('smart-assistant-router-entity-plan-scoped.jar',
               '417a88935f1999798bef3dedffa4ad8ce8e55b140fa3f54df2236cdac631c04b', 8083),
}
if __name__ == '__main__': guarded.main(sys.argv[1] if len(sys.argv) == 2 else '')
