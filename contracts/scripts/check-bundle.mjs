// Fails when a bundled spec in contracts/dist contains a $ref that is not a local "#/" reference.
// Generators such as openapi-typescript cannot resolve cross-file refs from the released tarball.
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const REF = /\$ref:\s*(?:'([^']*)'|"([^"]*)"|([^\s'"#][^\s]*|#[^\s]*))/g;

/** Returns every non-local $ref target found in the YAML text. */
export function externalRefs(text) {
  const found = [];
  for (const m of text.matchAll(REF)) {
    const target = m[1] ?? m[2] ?? m[3];
    if (!target.startsWith('#/')) found.push(target);
  }
  return found;
}

const here = dirname(fileURLToPath(import.meta.url));
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const dir = process.argv[2] ?? join(here, '..', 'dist');
  const files = existsSync(dir) ? readdirSync(dir).filter((f) => f.endsWith('.yaml')) : [];
  if (files.length === 0) {
    console.error(`no bundled specs in ${dir}; run make bundle-contract`);
    process.exit(1);
  }
  let failed = false;
  for (const file of files) {
    for (const target of externalRefs(readFileSync(join(dir, file), 'utf8'))) {
      console.error(`${file}: non-local $ref ${target}`);
      failed = true;
    }
  }
  if (failed) process.exit(1);
  // stderr, not stdout: this runs in `prepack`, and `npm pack --silent` output must be only the
  // tarball name (contract-publish reads it).
  console.error(`bundled specs are self-contained: ${files.join(', ')}`);
}
