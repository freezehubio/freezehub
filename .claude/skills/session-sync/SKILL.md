---
name: session-sync
description: Use when several Claude sessions are working this repository at once - before claiming a story, when starting work in a new worktree, or when a push, a branch name or a backlog edit collides with somebody else's. Shows every live session and every claim, takes a story id in a way other sessions can see, and names the files that conflict when two sessions edit them the same way. Claims and reports; it never merges, deletes, or touches another session's checkout.
---

# Session Sync

`next-story` answers **what to work on**. It is deliberately single-session: run it in three
windows and it will recommend the same story three times, because it reads `master` and the
backlog, and neither of those knows that another session started something forty seconds ago
and has not pushed yet.

This skill answers the other half: **who else is live, what have they taken, and may I take
this?**

## What git cannot see, and why that is the whole problem

A story is invisible to every other session for the entire window between "a session decided
to do it" and "a branch reached the remote". Everything collides in that window: two
sessions pick the same next id, two worktrees appear for one story, two entries land at the
end of `docs/08-backlog.md`.

So this skill reads three sources and trusts none of them alone:

| Source | Sees | Blind to |
|---|---|---|
| `ListAgents` | sessions alive on this machine, by name | what they are working on, anything on another machine |
| remote branches, open PRs | claims that reached the remote | work started but not pushed |
| `git worktree list` | work in progress on this machine | other machines, and whether a directory is still in use |

## Never

- **Never run `git checkout`, `git switch`, or a commit in the shared checkout** if another
  session has a story branch there. Changing its HEAD changes the files under a session that
  is mid-edit. Work in a worktree of your own, always.
- Never delete, reset or force-push another session's branch or worktree. If one looks
  abandoned, report it and let the operator decide.
- Never push to a branch another session holds, except by the conflict protocol below - and
  say so on the pull request afterwards.
- Never assume a claim is free because the branch is missing. Check the live sessions too.

## Procedure

### 1. Who is live

Call `ListAgents`. Peer sessions are addressable by name; `bg`/`idle` ones read messages when
they next wake, and `offline` ones never will.

### 2. What has been claimed

```bash
git fetch --prune origin
git branch -r --no-merged origin/master          # claims that are still open work
gh pr list --state open --json number,headRefName,mergeable,isDraft
git worktree list --porcelain
```

Cross-reference into one table - one row per story, with where it lives and what state it is
in. The rows worth calling out:

- **In flight** - a branch ahead of `origin/master`, or an open PR. Taken. Leave it.
- **Claimed but empty** - a remote branch with no commits ahead of `master` and no worktree.
  Either a claim made seconds ago or one abandoned weeks ago; the difference is the reflog
  date, and when it is old, ask before reusing the id.
- **Local only** - a worktree whose branch has no `origin/` counterpart. This is work no
  other session can see. If it is not yours, do not touch it; if it is yours, push the
  branch now, because that is what makes it visible.
- **Name mismatch** - a worktree directory whose name is not its branch
  (`freezehub-FZ-146` holding `FZ-168`). Harmless to git, and the reason a session
  eventually edits the wrong tree. Report it.
- **Stale** - the branch is already an ancestor of `origin/master`. Finished work, still on
  disk. List them together with the one command that removes them, and let the operator run
  it.

```bash
# stale worktrees, listed not removed
git worktree list --porcelain \
  | awk '/^worktree /{w=$2} /^branch /{print w"\t"$2}' | sed 's|refs/heads/||' \
  | while IFS=$'\t' read -r dir br; do
      git merge-base --is-ancestor "$br" origin/master 2>/dev/null && echo "MERGED $br ($dir)"
    done
```

### 3. Claim a story, in that order

The claim is the branch reaching the remote. Do it **before** writing any code, not after:

```bash
git ls-remote --exit-code origin "FZ-<id>" && echo "ALREADY CLAIMED - stop" || true
git worktree add ../freezehub-FZ-<id> -b FZ-<id> origin/master
git -C ../freezehub-FZ-<id> push -u origin FZ-<id>
```

A branch at `master` with no commits is a fine claim: it costs nothing, it is visible to
every session on every machine, and it is what `git branch -r` will show the next session
that checks.

Name the directory after the branch. `freezehub-FZ-188` holding `FZ-188` is the only
convention that survives thirty worktrees.

**The race this does not close, stated rather than hidden.** Between the `ls-remote` check
and the push there is a window in which another session can claim the same id, and because
both branches point at the same commit, both pushes succeed. Nothing in git prevents that.
What closes it is the next step, and detection afterwards: if two sessions end up on one id,
the one whose first *commit* reached the remote first keeps it, and the other re-claims. Do
not resolve it by force-pushing.

### 4. Say so

```
SendMessage to each live peer: "Taking FZ-<id> (<one line of what it is>) in
../freezehub-FZ-<id>."
```

Best effort, not a lock. It is the only thing that reaches a session which has started work
and not pushed - the exact case git is blind to - so it is worth the one message.

### 5. Work, then hand back

Finish under `CLAUDE.md` §9: branch, commits, push, `gh pr create`, report the link, stop.
**Do not merge, and do not branch the next story from an unmerged one** - branch from
`master` and say so.

## The files that collide, and how not to collide in them

Three sessions finishing three stories edit the same three files the same way. These are
where it has actually gone wrong, not where it might:

- **`docs/08-backlog.md`** - every story appends a `### FZ-<id>` entry, and every session
  appends it at the end of the file, so the merge is a conflict every time. Insert **before
  a stable heading** that already exists (`## Going to Market`), not at EOF, and keep entries
  in id order when resolving. Two entries at one anchor still conflict; the resolution is
  always *keep both*, never *keep mine*.
- **`docs/09-open-issues.md`** - a resolved issue moves to the table at the bottom. Insert
  after the header separator, and **assert the anchor before writing**: the separator must
  match exactly once in the file, and the line after the header must be the separator. An
  insert anchored at "the first `|---|---|---|`" once put four rows inside an unrelated
  cost table and went unnoticed through a merge.
- **`CLAUDE.md`, `.github/workflows/*.yml`** - small files everyone touches. Read before
  writing; never regenerate wholesale.

Anchored insert, with the assertion that makes it safe:

```bash
n=$(grep -c '^## Going to Market$' docs/08-backlog.md)
[ "$n" = 1 ] || { echo "anchor matched $n times - stop"; exit 1; }
```

## Resolving a conflict on a branch that is not yours

When the operator asks for it - and only then. This keeps the other session's checkout
untouched, which matters because it may be mid-edit:

```bash
git worktree add --detach ../freezehub-<branch>-merge origin/<branch>
cd ../freezehub-<branch>-merge && git merge origin/master
# resolve, keeping both sides of any backlog or open-issues conflict
git push origin HEAD:<branch>
```

Then comment on the pull request saying what conflicted, how it was resolved, and that their
local branch is now behind and needs a `git pull`. Do not edit their prose beyond the
conflict, except where the branch's own text asks for something to be done on merge.

## Report

Keep it to what changes a decision:

```text
Live        4 sessions - billing [2477ee], security [855cda], 2 shells
In flight   FZ-188 (billing, PR #61)   FZ-190 (local only, no branch pushed)
Stale       28 worktrees on merged branches - `git worktree remove` list printed below
Odd         freezehub-FZ-146 holds FZ-168; freezehub-FZ-138b duplicates FZ-138

Claimed     FZ-193, pushed to origin, worktree ../freezehub-FZ-193
Announced   billing, security
```

If nothing is live and nothing is in flight, say that - "you are the only session, take
anything" is a real answer and the cheapest one to act on.
