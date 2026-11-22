// Fails when a bundled spec contains a $ref that is not a local "#/" reference, because generators
// cannot resolve cross-file references in a released single-file spec.
// Usage: node check-bundle.mjs [file ...]   (default: dist/core.yaml dist/cart.yaml)
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const REF = /^\s*(?:-\s+)?["']?\$ref["']?\s*:\s*(.*?)\s*$/;

export function findExternalRefs(text) {
  const bad = [];
  text.split('\n').forEach((line, i) => {
    const m = REF.exec(line);
    if (!m) return;
    const target = m[1].replace(/^["']|["']$/g, '');
    if (!target.startsWith('#/')) bad.push({ line: i + 1, ref: target });
  });
  return bad;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..');
  const files = process.argv.length > 2 ? process.argv.slice(2) : ['core', 'cart'].map((n) => join(root, 'dist', `${n}.yaml`));
  let failed = false;
  for (const file of files) {
    const bad = findExternalRefs(readFileSync(file, 'utf8'));
    for (const b of bad) console.error(`${file}:${b.line}: non-local $ref ${b.ref}`);
    if (bad.length) failed = true;
  }
  if (failed) process.exit(1);
  console.log(`bundled specs are self-contained (${files.length} checked)`);
}
