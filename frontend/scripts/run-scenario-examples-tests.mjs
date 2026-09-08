import { buildSync } from 'esbuild';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

// Match the production bundler's CommonJS interop for the UI library.
const root = fileURLToPath(new URL('../', import.meta.url));
const outfile = resolve(root, 'node_modules/.cache/scenario-examples.test.cjs');
buildSync({ absWorkingDir: root, entryPoints: ['scripts/scenario-examples.test.tsx'],
  bundle: true, platform: 'node', format: 'cjs', packages: 'external', outfile });
await import(pathToFileURL(outfile).href);
