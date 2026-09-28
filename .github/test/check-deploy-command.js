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
  // **Hostile on purpose** (`FZ-220`). These were empty strings, on the reasoning that an
  // unset repository variable still arrives as one. True — and exactly why this check missed
  // the defect it exists to prevent: an empty value cannot contain an `&`, so the one case
  // that broke production was the one case never rendered.
  //
  // A real booking URL ending `&ctz=America%2FBogota` turned its `echo` into a backgrounded
  // command plus a stray assignment. The line never reached `.env`, everything exited 0, and
  // the deploy went green.
  //
  // Each value now carries something a shell would act on. Keep it that way: a fixture that
  // cannot fail is not a fixture.
  NOTIFICATIONS_EMAIL_FROM: 'no-reply@example.com',
  DEMO_EMAIL_TO: 'hola@example.com',
  DEMO_ACKNOWLEDGE_FROM: 'founder@example.com',
  DEMO_BOOKING_URL: 'https://cal.example.com/book?src=a%40b.com&ctz=America%2FBogota&x=1',
  DEMO_POLICY_URL: "https://example.com/p?q=a;b'c d`e",
  MAIL_HOST: 'email-smtp.us-east-2.amazonaws.com',
  MAIL_PORT: '587',
};

/** Single-quote for the harness itself; the fixture deliberately contains quotes. */
function shellQuote(value) {
  return "'" + String(value).split("'").join("'\\''") + "'";
}

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

// The ENV_B64 construction is now the thing most worth testing (`FZ-220`), so it is spliced
// in ahead of the command array rather than faked. It is self-contained — it reads only
// environment variables — unlike the other _B64 assignments, which read files and are
// supplied by the fixture above.
const envStart = lines.findIndex((l) => l.includes("ENV_B64=$(printf"));
if (envStart < 0) {
  fail('could not find the ENV_B64 construction in deploy-singlebox.yml',
       'The .env is meant to be assembled on the runner and shipped as base64. If that\n' +
       'changed, update this check with it — see FZ-220 for what it prevents.');
}
let envEnd = envStart;
while (envEnd < lines.length && !lines[envEnd].includes("| base64 | tr -d")) envEnd += 1;
const envBlock = lines.slice(envStart, envEnd + 1);

const block = envBlock.concat(lines.slice(start, end));
const cmdIndex = block.findIndex((l) => l.includes('--parameters commands='));
block[cmdIndex] = block[cmdIndex].replace(/^\s*--parameters commands=/, 'COMMANDS=');
block[block.length - 1] = block[block.length - 1].replace(/\s*\\$/, '');

const header = ['#!/bin/bash', 'set -u']
  .concat(Object.entries(ENV).map(([k, v]) => `${k}=${shellQuote(v)}`))
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

// **Parsing is not enough** (`FZ-220`). The defect this check failed to catch produced a
// script that parsed perfectly and silently dropped a line, so the only useful question is
// whether each value the runner supplies actually survives into the file.
const shipped = fs.readFileSync(path.join(tmp, 'script.sh'), 'utf8');
const b64 = (shipped.match(/echo ([A-Za-z0-9+/=]{40,}) \| base64 -d > \.env/) || [])[1];
if (!b64) {
  fail('the .env is no longer shipped as base64',
       'Values assembled inside the remote script are re-parsed by its shell, which is how\n' +
       'a URL containing an ampersand vanished without failing anything. See FZ-220.');
}

const envFile = Buffer.from(b64, 'base64').toString('utf8').split('\n');
for (const [key, value] of Object.entries(ENV)) {
  if (/_B64$|^INSTANCE_ID$|^IMAGE_TAG$|^REGISTRY$|^ENVIRONMENT$/.test(key)) continue;
  const line = envFile.find((l) => l.startsWith(key + '='));
  if (line === undefined) fail(key + ' never reaches .env', envFile.join('\n'));
  if (line !== key + '=' + value) {
    fail(key + ' is mangled on the way to .env',
         'expected: ' + key + '=' + value + '\nactual:   ' + line);
  }
}

fs.rmSync(tmp, { recursive: true, force: true });
console.log(`PASS  ${commands.length} commands: valid JSON, valid bash, secrets read on the box`);
console.log(`      and every runner-supplied value survives into .env byte-for-byte`);
