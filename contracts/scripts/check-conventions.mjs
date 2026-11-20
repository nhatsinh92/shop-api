// Fails when an operation in core.yaml or cart.yaml breaks the contract conventions
// (see contracts/CLAUDE.md). Specs are bundled to JSON with Redocly so $refs are resolved.
//   node contracts/scripts/check-conventions.mjs
import { execFileSync } from 'node:child_process';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const METHODS = ['get', 'put', 'post', 'delete', 'options', 'head', 'patch', 'trace'];
const JSON_TYPE = /^application\/(?:[\w.+-]+\+)?json$/;
const PROBLEM_TYPE = 'application/problem+json';

function deref(doc, node) {
  let n = node;
  for (let i = 0; n && typeof n === 'object' && typeof n.$ref === 'string' && i < 20; i++) {
    n = n.$ref.replace(/^#\//, '').split('/').reduce((o, k) => o?.[k.replace(/~1/g, '/').replace(/~0/g, '~')], doc);
  }
  return n;
}

const hasExample = (media) =>
  media?.example !== undefined || Object.keys(media?.examples ?? {}).length > 0;

const usesProblem = (media) => typeof media?.schema?.$ref === 'string' && media.schema.$ref.endsWith('/schemas/Problem');

function problemExamples(media) {
  const out = [];
  if (media?.example !== undefined) out.push(media.example);
  for (const ex of Object.values(media?.examples ?? {})) if (ex?.value !== undefined) out.push(ex.value);
  return out;
}

/** Returns a list of violation messages for one bundled spec. `codes` is the set of known error codes. */
export function checkSpec(doc, codes) {
  const errors = [];
  for (const [path, item] of Object.entries(doc.paths ?? {})) {
    for (const method of METHODS.filter((m) => item?.[m])) {
      const op = item[method];
      const where = `${method.toUpperCase()} ${path}`;
      if (!op.operationId) errors.push(`${where}: missing operationId`);
      if (!Array.isArray(op.tags) || op.tags.length === 0) errors.push(`${where}: missing tag`);

      const body = deref(doc, op.requestBody);
      for (const [type, media] of Object.entries(body?.content ?? {})) {
        if (JSON_TYPE.test(type) && !hasExample(media)) errors.push(`${where}: JSON request body has no example`);
      }

      // Only `health` operations may rely on a `default` problem response; all others need an explicit 4xx.
      const allowsDefault = Array.isArray(op.tags) && op.tags.includes('health');
      let hasProblemResponse = false;
      for (const [status, rawResp] of Object.entries(op.responses ?? {})) {
        const resp = deref(doc, rawResp);
        const content = resp?.content ?? {};
        if (/^2/.test(status)) {
          for (const [type, media] of Object.entries(content)) {
            if (JSON_TYPE.test(type) && !hasExample(media)) errors.push(`${where}: ${status} JSON response has no example`);
          }
        }
        if (/^4/.test(status) || (allowsDefault && status === 'default')) {
          const media = content[PROBLEM_TYPE];
          if (usesProblem(media)) {
            hasProblemResponse = true;
            for (const ex of problemExamples(media)) {
              if (!codes.has(ex?.code)) errors.push(`${where}: ${status} problem example uses code ${ex?.code} which is not in x-error-codes`);
            }
          }
        }
      }
      if (!hasProblemResponse) errors.push(`${where}: no explicit 4xx response using application/problem+json with Problem (only health operations may use default)`);
    }
  }
  return errors;
}

export const catalogue = (errorsDoc) => new Set(Object.keys(errorsDoc?.['x-error-codes'] ?? {}));

function bundle(spec) {
  const out = execFileSync('npx', ['--yes', '@redocly/cli@2.82.0', 'bundle', spec, '--ext', 'json'], {
    env: { ...process.env, REDOCLY_TELEMETRY: 'off' },
    stdio: ['ignore', 'pipe', 'inherit'],
    maxBuffer: 64 * 1024 * 1024,
  });
  return JSON.parse(out.toString('utf8'));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const openapi = join(dirname(fileURLToPath(import.meta.url)), '..', 'openapi');
  const codes = catalogue(bundle(join(openapi, 'errors.yaml')));
  if (codes.size === 0) {
    console.error('errors.yaml: x-error-codes is missing or empty');
    process.exit(1);
  }
  let failed = false;
  for (const name of ['core', 'cart']) {
    const errors = checkSpec(bundle(join(openapi, `${name}.yaml`)), codes);
    for (const e of errors) console.error(`${name}.yaml: ${e}`);
    failed ||= errors.length > 0;
  }
  if (failed) process.exit(1);
  console.log('contract conventions check passed');
}
