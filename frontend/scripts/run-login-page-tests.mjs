import { buildSync } from 'esbuild';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const outfile = resolve(root, 'node_modules/.cache/login-page.test.cjs');
buildSync({ absWorkingDir: root, entryPoints: ['scripts/login-page.test.tsx'],
  bundle: true, platform: 'node', format: 'cjs', packages: 'external', outfile,
  define: { 'import.meta.env': '{}' } });
await import(pathToFileURL(outfile).href);
