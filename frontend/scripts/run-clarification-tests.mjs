import { buildSync } from 'esbuild';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
const root = fileURLToPath(new URL('../', import.meta.url));
const outfile = resolve(root, 'node_modules/.cache/clarification.test.cjs');
buildSync({ absWorkingDir: root, entryPoints: ['scripts/clarification.test.tsx'], bundle: true,
  platform: 'node', format: 'cjs', packages: 'external', outfile,
  define: { 'import.meta.env': '{}' }, loader: { '.css': 'empty' } });
await import(pathToFileURL(outfile).href);
