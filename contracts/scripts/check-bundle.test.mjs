// Fixture tests for check-bundle.mjs: a self-contained bundle passes, cross-file refs fail.
import test from 'node:test';
import assert from 'node:assert/strict';
import { externalRefs } from './check-bundle.mjs';

test('a bundle with only local refs passes', () => {
  const text = [
    'paths:',
    '  /a:',
    '    get:',
    '      responses:',
    "        '404':",
    "          $ref: '#/components/responses/NotFound'",
    '        "400":',
    '          $ref: "#/components/responses/BadRequest"',
    '        "200":',
    '          $ref: #/components/responses/Ok',
  ].join('\n');
  assert.deepEqual(externalRefs(text), []);
});

test('a cross-file ref fails', () => {
  const text = "schema:\n  $ref: './errors.yaml#/components/schemas/Problem'\n";
  assert.deepEqual(externalRefs(text), ['./errors.yaml#/components/schemas/Problem']);
});

test('an unquoted and a double-quoted cross-file ref fail', () => {
  const text = 'a:\n  $ref: errors.yaml#/x\nb:\n  $ref: "errors.yaml#/y"\n';
  assert.deepEqual(externalRefs(text), ['errors.yaml#/x', 'errors.yaml#/y']);
});
