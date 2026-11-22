// Tests for check-bundle.mjs run as a command, the way `prepack` runs it: on success stdout stays
// empty, so `npm pack --silent` prints only the tarball name that contract-publish reads (#37).
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const script = join(dirname(fileURLToPath(import.meta.url)), 'check-bundle.mjs');

function run(files) {
  const dir = mkdtempSync(join(tmpdir(), 'check-bundle-'));
  try {
    for (const [name, text] of Object.entries(files)) writeFileSync(join(dir, name), text);
    return spawnSync(process.execPath, [script, dir], { encoding: 'utf8' });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test('a self-contained bundle exits 0 and writes nothing to stdout', () => {
  const result = run({ 'core.yaml': "a:\n  $ref: '#/components/schemas/A'\n" });
  assert.equal(result.status, 0);
  assert.equal(result.stdout, '');
  assert.match(result.stderr, /bundled specs are self-contained: core\.yaml/);
});

test('a cross-file ref exits 1 and names the ref on stderr', () => {
  const result = run({ 'cart.yaml': "a:\n  $ref: './errors.yaml#/components/schemas/Problem'\n" });
  assert.equal(result.status, 1);
  assert.equal(result.stdout, '');
  assert.match(result.stderr, /cart\.yaml: non-local \$ref \.\/errors\.yaml#\/components\/schemas\/Problem/);
});
