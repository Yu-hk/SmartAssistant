import { buildSync } from 'esbuild';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

// The UI library uses CommonJS exports; compile this SSR integration test as CJS,
// matching the production bundler's interop rather than Node's ESM named-export heuristic.
const root = fileURLToPath(new URL('../', import.meta.url));
const outfile = resolve(root, 'node_modules/.cache/chat-steps-layout.test.cjs');
buildSync({ absWorkingDir: root, entryPoints: ['scripts/chat-steps-layout.test.tsx'],
  bundle: true, platform: 'node', format: 'cjs', packages: 'external', outfile });
await import(pathToFileURL(outfile).href);
