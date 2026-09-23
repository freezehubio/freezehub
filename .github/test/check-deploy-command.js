#!/usr/bin/env node
/*
 * The deploy command the box actually receives (FZ-208).
 *
 * `deploy-singlebox.yml` builds a JSON array of shell commands inside a double-quoted shell
 * string inside YAML. Three layers of quoting, and nothing validated the result — so an
 * escaping mistake was discoverable only by deploying. It duly was: `\\\$(` produced `\$(`
 * in the JSON, bash read `\$` as an escaped dollar, and the bare `(` was a syntax error on
 * line 14 of a script nobody could see.
 *
 * **The AWS CLI accepted that JSON even though it is invalid** — `\$` is not a JSON escape
 * — and passed it through, which is why the failure surfaced as a shell error on the box
 * rather than as a rejected API call. So "the deploy was accepted" proves nothing, and this
 * check does not use the CLI.
 *
 * It renders the array exactly as the workflow's shell would, parses it as JSON, and runs
 * `bash -n` over the joined script. Values are shaped like the real ones; none are secret.
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const WORKFLOW = path.join(__dirname, '..', 'workflows', 'deploy-singlebox.yml');

// Shaped like production, so a quoting bug that only bites on a value containing `/`, `.`
// or `:` is still caught. Nothing here is a credential.
const ENV = {
  COMPOSE_B64: 'Y29tcG9zZQ==',
  CADDY_B64: 'Y2FkZHk=',
  BACKUP_B64: 'YmFja3Vw',
  BACKUP_SERVICE_B64: 'c3ZjCg==',
  BACKUP_TIMER_B64: 'dG1yCg==',
  INSTANCE_ID: 'i-0123456789abcdef0',
  IMAGE_TAG: 'abc1234',
  REGISTRY: '000000000000.dkr.ecr.us-east-2.amazonaws.com/freezehub-beta',
  AWS_REGION: 'us-east-2',
  ENVIRONMENT: 'beta',
  APP_DOMAIN: 'app.example.com',
  API_DOMAIN: 'api.example.com',
  COGNITO_USER_POOL_ID: 'us-east-2_EXAMPLE00',
  COGNITO_CLIENT_ID: 'exampleclientid0000000000',
  BACKUP_BUCKET: 'freezehub-beta-backups-000000000000',
};

function fail(msg, detail) {
  console.error(`FAIL  ${msg}`);
  if (detail) console.error(String(detail).split('\n').map((l) => `      ${l}`).join('\n').trimEnd());
  process.exit(1);
}

const lines = fs.readFileSync(WORKFLOW, 'utf8').split('\n');
const start = lines.findIndex((l) => l.includes('--parameters commands='));
const end = lines.findIndex((l, i) => i > start && l.includes("--query 'Command.CommandId'"));
if (start < 0 || end < 0) {
  fail('could not find the `--parameters commands=` block in deploy-singlebox.yml',
       'If the send-command step was restructured, update this check with it — do not delete it.');
}

const block = lines.slice(start, end);
block[0] = block[0].replace(/^\s*--parameters commands=/, 'COMMANDS=');
block[block.length - 1] = block[block.length - 1].replace(/\s*\\$/, '');

const header = ['#!/bin/bash', 'set -u']
  .concat(Object.entries(ENV).map(([k, v]) => `${k}=${v}`))
  .join('\n');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'freezehub-deploy-check-'));
const renderer = path.join(tmp, 'render.sh');
fs.writeFileSync(renderer, `${header}\n${block.join('\n')}\nprintf "%s" "$COMMANDS"\n`);

let rendered;
try {
  rendered = execFileSync('bash', [renderer], { encoding: 'utf8' });
} catch (e) {
  fail('the workflow\'s own shell could not render the commands array', e.stderr || e.message);
}

let commands;
try {
  commands = JSON.parse(rendered);
} catch (e) {
  fail(`the rendered commands array is not valid JSON — ${e.message}`,
       `A backslash before a character JSON does not escape (\\$ is the usual one) is the\n` +
       `cause. The AWS CLI accepts this and passes it through, so it fails on the box, not here.\n\n` +
       `Rendered:\n${rendered}`);
}
if (!Array.isArray(commands) || commands.length === 0) fail('the commands array is empty or not an array');

const script = path.join(tmp, 'script.sh');
fs.writeFileSync(script, `${commands.join('\n')}\n`);
try {
  execFileSync('bash', ['-n', script], { stdio: 'pipe' });
} catch (e) {
  const numbered = commands.map((c, i) => `${String(i + 1).padStart(3)}  ${c}`).join('\n');
  fail('the script the box would run is not valid bash', `${e.stderr}\nThe script:\n${numbered}`);
}

// A command substitution has to survive into the script: the runner's role cannot read the
// parameters, only the instance role can. If these were evaluated on the runner they would
// be empty, and the box would get a blank password rather than an error.
for (const name of ['DATABASE_PASSWORD', 'ENCRYPTION_KEY']) {
  const line = commands.find((c) => c.startsWith(`echo ${name}=`));
  if (!line) fail(`no command writes ${name} to .env`);
  if (!line.includes('$(aws ssm get-parameter')) {
    fail(`${name} is not read on the box`,
         `Expected an unevaluated $(aws ssm get-parameter ...) in:\n${line}`);
  }
}

fs.rmSync(tmp, { recursive: true, force: true });
console.log(`PASS  ${commands.length} commands: valid JSON, valid bash, secrets read on the box`);
