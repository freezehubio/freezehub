---
name: next-story
description: Use before starting any backlog item, or when asking what to work on next. Syncs with the remote, verifies what actually landed on master rather than what merely merged, recomputes each open story's blockers from real statuses, and reports the candidates with a recommendation. Reports only — it never creates a branch, commits, or edits the backlog.
---

# Next Story

Answers one question: **given what has actually landed on `master`, what should be worked on next?**

`CLAUDE.md` §9 requires a sync-and-verify pass before every story. Done by hand it is
routinely got wrong, because the two things that go wrong are both invisible unless
specifically looked for:

- a pull request merged while a later commit was still being pushed leaves that commit
  behind, and the merge commit looks identical either way;
- a story's `**Blocked by:**` line is written once and never revisited, so it goes on
  claiming a blocker that finished weeks ago.

This skill looks for both.

## Never

- Do not create the branch. Do not commit, push, or open a pull request.
- Do not edit `docs/08-backlog.md` or `docs/09-open-issues.md`, including to correct a
  stale blocker line this skill finds. Report it; the operator decides.
- Do not stash, reset, or clean anything to make the checks run. If the tree is dirty,
  say so and stop.

## Procedure

### 1. State of the working copy

```bash
git branch --show-current
git status --porcelain
```

Report both. If the tree is not clean, or the current branch is not `master`, say so
plainly and continue read-only — the remaining steps still work and their answer is still
useful. Do not offer to tidy it.

### 2. Sync

```bash
git fetch --prune origin
git rev-list --left-right --count master...origin/master
```

Fast-forward only when the tree is clean and the branch is `master`:

```bash
git pull --ff-only origin master
```

If the fast-forward is refused, report the divergence and stop. Do not merge or rebase.

### 3. What actually landed

Merged pull requests, newest first:

```bash
gh pr list --state merged --limit 20 \
  --json number,title,headRefName,headRefOid,mergedAt
```

For each `headRefOid`, confirm the commit is present locally, then confirm it is an
ancestor of `origin/master`:

```bash
git cat-file -e <headRefOid>^{commit} 2>/dev/null \
  && git merge-base --is-ancestor <headRefOid> origin/master
```

- Exit 0 — the PR's head is on `master`. Everything it carried landed.
- Non-zero from `merge-base` — **the PR merged but its head commit did not land.** This is
  the §9 failure. Name the PR and the SHA; the branch it came from is where that work
  survives.
- `git cat-file` fails — the object is not local. Try `git fetch origin <headRefOid>`
  once; if that fails too, report it as unverifiable rather than as passing.

Then, work that exists only on a branch:

```bash
git branch -r --no-merged origin/master
```

List each one. A remote branch that never merged is either abandoned or forgotten, and
starting new work without knowing which is how a diff nobody can review gets produced.

### 4. Read the backlog

```bash
grep -nE '^### FZ-[0-9]+' docs/08-backlog.md
grep -nE '^\*\*Status:\*\*' docs/08-backlog.md
grep -nE '^### OI-[0-9]+' docs/09-open-issues.md
```

Pair each story heading with the status line that follows it. Treat `DONE`, `SUPERSEDED`
and `NOT NEEDED` as closed; everything else is open.

### 5. Recompute blockers — do not trust the line

For every open story, read its `**Blocked by:**` list and look up the **current status of
each story it names**.

- All named blockers closed, and the line still present → **the line is stale.** The story
  is available. Report the staleness as a finding, with the evidence: which blockers, and
  that they are DONE.
- Any named blocker still open → genuinely blocked. Say which one.
- `**Blocked on:**` naming a human action or an external event rather than a story → not
  schedulable by this skill. List it separately.
- `DEFERRED` → never recommend it. List it under held, with its stated reason.

**The status line is not the whole dependency.** Some stories state a dependency only in
their prose — `FZ-046` says *"Depends on a Cognito user pool existing, so sequence with
`FZ-063`"* and carries no `**Blocked by:**` line at all. Read the story body, not only its
status line, and report a prose dependency as a caveat on the recommendation rather than
silently treating the story as free.

### 6. Rank and report

Order the available candidates by how much they unblock: a story other open stories name
as their blocker comes before one nothing depends on. Break ties toward the story whose
own dependencies landed most recently, since that is the work the last merge made possible.

Report in this shape, and keep it short:

```text
Synced        master, up to date with origin, tree clean
Landed        PR #52 FZ-145 ✓  PR #51 FZ-144 ✓  … all heads on master
Unmerged      origin/FZ-103, origin/FZ-113 — on a branch only
Findings      FZ-123 "Blocked by FZ-121, FZ-122" is stale; both DONE

Available     FZ-046  unblocks FZ-082 and FZ-128
              FZ-123  unblocked as of FZ-122; resolves OI-15
              FZ-146  no dependents

Blocked       FZ-082  needs FZ-046      FZ-111  needs a public route
              FZ-099  one dispatch      FZ-138  two human actions
Held          FZ-088, FZ-096

Recommend     FZ-046 — two open stories name it, and nothing names them.
              git checkout master && git pull --ff-only origin master
              git checkout -b FZ-046
```

Print the branch command; do not run it.

## When there is nothing to recommend

Say so. "Every open story is blocked on a human action" is a real and useful answer, and
inventing a candidate to avoid an empty list is worse than an empty list. If the only
available work is a stale-blocker finding, report the finding and stop.
