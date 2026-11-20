// Fails when info.version in any spec differs from contracts/package.json.
// The package version is the single contract version; bump all of them together.
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const { version } = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
const dir = join(root, 'openapi');
let failed = false;
for (const file of readdirSync(dir).filter((f) => f.endsWith('.yaml'))) {
  const match = readFileSync(join(dir, file), 'utf8').match(/^info:\n(?:[ \t].*\n)*?[ \t]+version:[ \t]*['"]?([^'"\s]+)/m);
  const specVersion = match?.[1];
  if (specVersion !== version) {
    console.error(`${file}: info.version is ${specVersion ?? 'missing'}, package.json is ${version}`);
    failed = true;
  }
}
if (failed) process.exit(1);
console.log(`contract version ${version} is consistent`);
