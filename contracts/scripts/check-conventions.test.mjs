// Fixture tests for check-conventions.mjs: one good spec passes, one bad spec per rule fails.
import test from 'node:test';
import assert from 'node:assert/strict';
import { checkSpec, catalogue } from './check-conventions.mjs';

const codes = catalogue({ 'x-error-codes': { NOT_FOUND: {}, INTERNAL_ERROR: {} } });
const problem = (code = 'NOT_FOUND') => ({
  description: 'problem',
  content: {
    'application/problem+json': {
      schema: { $ref: '#/components/schemas/Problem' },
      examples: { default: { value: { code } } },
    },
  },
});
const good = () => ({
  paths: {
    '/things': {
      post: {
        operationId: 'createThing',
        tags: ['things'],
        requestBody: { content: { 'application/json': { schema: {}, example: { name: 'a' } } } },
        responses: {
          201: { description: 'ok', content: { 'application/json': { schema: {}, example: { id: 1 } } } },
          404: problem(),
        },
      },
    },
  },
});
const op = (doc) => doc.paths['/things'].post;

test('a spec that follows every convention passes', () => {
  assert.deepEqual(checkSpec(good(), codes), []);
});

const defaultOnly = (tags) => {
  const doc = good();
  delete op(doc).responses[404];
  op(doc).tags = tags;
  op(doc).responses.default = { $ref: '#/components/responses/Err' };
  doc.components = { responses: { Err: problem('INTERNAL_ERROR') } };
  return doc;
};

test('a health operation with only a default problem response passes', () => {
  assert.deepEqual(checkSpec(defaultOnly(['health']), codes), []);
});

test('a non-health operation with only a default problem response fails', () => {
  const errors = checkSpec(defaultOnly(['things']), codes);
  assert.equal(errors.length, 1);
  assert.match(errors[0], /POST \/things: no explicit 4xx/);
});

const bad = {
  'missing operationId': (d) => delete op(d).operationId,
  'missing tag': (d) => delete op(d).tags,
  'JSON request body without example': (d) => delete op(d).requestBody.content['application/json'].example,
  '2xx JSON response without example': (d) => delete op(d).responses[201].content['application/json'].example,
  'no 4xx response using Problem': (d) => delete op(d).responses[404],
  '4xx response not using Problem': (d) => {
    op(d).responses[404] = { description: 'x', content: { 'application/json': { schema: {}, example: {} } } };
  },
  'problem example with unknown code': (d) => {
    op(d).responses[404] = problem('MADE_UP_CODE');
  },
};
for (const [name, mutate] of Object.entries(bad)) {
  test(`fails on ${name}`, () => {
    const doc = good();
    mutate(doc);
    const errors = checkSpec(doc, codes);
    assert.ok(errors.length > 0, 'expected a violation');
    assert.match(errors[0], /POST \/things/);
  });
}
