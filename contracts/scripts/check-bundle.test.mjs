import { test } from 'node:test';
import assert from 'node:assert/strict';
import { findExternalRefs } from './check-bundle.mjs';

test('passes a spec with only local refs', () => {
  const spec = "a:\n  $ref: '#/components/schemas/Money'\nb:\n  - $ref: \"#/components/responses/NotFound\"\n";
  assert.deepEqual(findExternalRefs(spec), []);
});

test('fails a cross-file ref', () => {
  const spec = "a:\n  $ref: './errors.yaml#/components/schemas/Problem'\n";
  assert.deepEqual(findExternalRefs(spec), [{ line: 2, ref: './errors.yaml#/components/schemas/Problem' }]);
});

test('fails a ref to a bare file', () => {
  assert.equal(findExternalRefs('x:\n  $ref: errors.yaml\n').length, 1);
});
