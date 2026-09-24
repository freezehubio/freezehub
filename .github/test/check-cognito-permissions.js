#!/usr/bin/env node
/*
 * Every Cognito operation the backend calls is one both deployments allow (FZ-211).
 *
 * FZ-082 added `adminDeleteUser` to CognitoIdentityProvider — to undo a half-finished signup
 * and to purge signups nobody verified — while infra/singlebox/iam.tf deliberately granted
 * only create and get. Nothing connected the two. In production the delete was refused, the
 * adapter logged it and carried on, as it must, and every abandoned signup kept its email
 * address in the pool for good. Tests could not see it: they run against a fake provider
 * that needs no permissions.
 *
 * So this reads the calls out of the Java and the grants out of both postures' Terraform, and
 * fails when a call has no grant. It is deliberately one-directional: a grant the code does
 * not use is a tidiness question, a call the infrastructure refuses is an outage.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..');

// Where the backend talks to Cognito. Every call goes through the one client field.
const SOURCES = [
  'backend/src/main/java/com/freezhub/shared/security/CognitoIdentityProvider.java',
];

// Both postures must grant the same set: the application does not know which it runs on.
const POLICIES = ['infra/singlebox/iam.tf', 'infra/ecs/backend.tf'];

function read(relative) {
  return fs.readFileSync(path.join(ROOT, relative), 'utf8');
}

// `cognito.adminDeleteUser(` -> `cognito-idp:AdminDeleteUser`
const calls = new Set();
for (const source of SOURCES) {
  for (const [, method] of read(source).matchAll(/\bcognito\.(\w+)\(/g)) {
    calls.add(`cognito-idp:${method[0].toUpperCase()}${method.slice(1)}`);
  }
}

if (calls.size === 0) {
  // A rename that moved the calls elsewhere would otherwise turn this into a check that
  // always passes.
  console.error(`no Cognito calls found in ${SOURCES.join(', ')} — has the adapter moved?`);
  process.exit(1);
}

let failed = false;
for (const policy of POLICIES) {
  const granted = new Set(read(policy).match(/cognito-idp:\w+/g) ?? []);
  for (const call of calls) {
    if (granted.has(call)) {
      console.log(`  ok   ${policy} grants ${call}`);
    } else {
      console.error(`  FAIL ${policy} does not grant ${call}, which the backend calls`);
      failed = true;
    }
  }
}

if (failed) {
  console.error('\nThe backend would be refused at runtime. Grant the action, or stop calling it.');
  process.exit(1);
}
console.log(`\nall ${calls.size} Cognito calls are granted in both postures`);
