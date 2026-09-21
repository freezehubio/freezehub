# FreezeHub

**One answer to "can I deploy right now?" — and a record that it was given.**

---

## The problem

Most engineering organizations freeze deployments sometimes: a release week, year end, a
peak trading period, an audit. Almost none of them can answer four questions about it.

| | Today |
|---|---|
| Is there a freeze on right now? | Slack scrollback, a calendar invite, asking someone |
| Does it apply to *my* service? | Reading a paragraph and guessing |
| Did anyone deploy during it? | Nobody can answer |
| Who lifted the freeze, and when? | Nobody can answer |

The first two cost an engineer ten minutes and a manager an interruption. The last two cost
you the incident review.

## What FreezeHub does

**Announce a freeze once**, scoped to the teams, applications and environments it actually
covers. Everyone who needs to know is told, in the channel they already read.

**Your pipeline asks before it deploys.** One HTTPS call returns `ALLOW` or `BLOCK`, with
the reason. A container image drops into GitHub Actions, GitLab CI or Jenkins — the same
image in all three, so two teams on two CI systems get the same answer from the same freeze.

**Every check is recorded.** Who deployed, what, from which pipeline run, and what the answer
was. Every administrative change — created, edited, lifted — is in an audit trail that is
never purged.

## What FreezeHub does not do

**It cannot stop your deployment, and it does not try to.** Enforcement stays in your
pipeline: it asks, and your pipeline decides. We think that is the right boundary — a tool
that could stop your deploys is a tool that can take your production down, and a hard
dependency on a third party in every release is not something we would accept either.

The shipped connector fails closed by default, so the safe behaviour is what you get without
configuring anything.

## What it costs

Priced per **registered application**. Users, teams, environments, freezes and policy
evaluations are unlimited on every plan.

| | Free | Starter | Growth | Scale | Enterprise |
|---|---|---|---|---|---|
| **Freezes actually block** | advisory only | yes | yes | yes | yes |
| Applications | 5 | 10 | 50 | 200 | unlimited |
| Per month | $0 | $99 | $349 | $899 | from $2,000 |
| Annual (2 months free) | — | $990 | $3,490 | $8,990 | contract |
| Check history | 7 days | 90 days | 1 year | 1 year | up to 10 years |
| Audit trail | unlimited | unlimited | unlimited | unlimited | unlimited |
| Support | docs | email | business hours | business hours | SLA |

**Not per seat**, because a freeze half your organization has not heard about is not a
freeze, and we are not going to charge you for telling everyone. **Not per evaluation**,
because metering the check is how a deployment slips through one.

## Where we are

FreezeHub is pre-launch and we are looking for design partners. That means a demo, a
conversation about whether it fits, and an organization we set up for you — not a signup
form. You would be among the first customers, with the access to us that implies.

**Next step:** a four-minute demo of a freeze being announced, enforced in a pipeline, and
audited afterwards.
