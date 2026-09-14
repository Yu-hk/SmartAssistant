import { buildSync } from 'esbuild';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
const root = fileURLToPath(new URL('../', import.meta.url));
const outfile = resolve(root, 'node_modules/.cache/product-intake.test.cjs');
buildSync({ absWorkingDir: root, entryPoints: ['scripts/product-intake.test.tsx'], bundle: true,
  platform: 'node', format: 'cjs', packages: 'external', loader: { '.css': 'empty' }, outfile });
await import(pathToFileURL(outfile).href);
