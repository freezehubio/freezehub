#!/usr/bin/env node
/*
 * Nothing in `legal/` may be published with a placeholder still in it (`FZ-210`).
 *
 * They mark facts only the company knows, and several are required by statute — a Política
 * de Tratamiento missing Decreto 1377 Art. 13's identifying details is not a deficient
 * Política, it is not one. This is the same shape of guard as the others in this repository:
 * the failure it prevents is a document that looks finished and is not.
 *
 * Exits 0 by default and reports, because the placeholders are *supposed* to be there until
 * counsel has been through it. `--strict` is for whatever eventually publishes these.
 */
const fs = require('fs');
const path = require('path');

const dir = __dirname;
const strict = process.argv.includes('--strict');
const PLACEHOLDER = /«([^»]+)»/g;

const found = new Map();
let files = 0;

for (const name of fs.readdirSync(dir).sort()) {
  if (!name.endsWith('.md')) continue;
  // The README lists every placeholder on purpose, to say what each one is. Counting it would
  // make --strict fail forever, however complete the documents themselves were.
  if (name === 'README.md') continue;
  files += 1;
  const text = fs.readFileSync(path.join(dir, name), 'utf8');
  for (const [, token] of text.matchAll(PLACEHOLDER)) {
    if (!found.has(token)) found.set(token, new Set());
    found.get(token).add(name);
  }
}

if (found.size === 0) {
  console.log(`No placeholders left in ${files} documents.`);
  process.exit(0);
}

console.log(`${found.size} placeholder(s) still to fill, across ${files} documents:\n`);
for (const [token, where] of [...found].sort()) {
  console.log(`  «${token}»`);
  console.log(`      ${[...where].sort().join(', ')}`);
}
console.log('\nSee legal/README.md for what each one is and where it comes from.');

if (strict) {
  console.error('\n--strict: refusing to treat these as publishable.');
  process.exit(1);
}
