# Discovery

Internal. What to ask, what the answers mean, and who to walk away from.

The aim is not to establish that they have freezes. It is to find out **which of the four
questions they cannot answer**, because that is what they are buying and it determines the
whole shape of the deal.

## The one question to open with

> **"The week before a release, how does an engineer find out whether they're allowed to deploy?"**

Situational, not categorical. Nobody searches for "deployment freeze management", so a
question naming the category gets a blank look while a question naming the *situation* gets
a story. Let them tell it.

Listen for their word for it — *release lockdown, change freeze, code freeze, peak
moratorium, the golden hour*. **Use their word for the rest of the cycle.** Yours is not
better and switching costs you the recognition.

## The four diagnostic questions

Map directly to what is sold. Ask in this order; the discomfort builds.

**1. Is there a freeze on right now?**
> "If I asked three of your engineers right now, would I get the same answer?"

Laughter is a qualified deal. Confident yes means either a small shop where Slack genuinely
works, or someone who has not checked.

**2. Does it apply to my service?**
> "When a freeze is announced, how does someone with four services work out which are covered?"

Listen for *"they ask"* or *"they read the message and guess"*. A team that has already
tried to write scoping rules into a Slack message has felt this one properly.

**3. Did anyone deploy during it?**
> "After your last freeze — could you produce a list of everything that deployed while it was on?"

**The pivot question.** Almost nobody can. The pause before the answer is the moment the
conversation changes, so ask it and then stop talking.

**4. Who lifted it, and when?**
> "Who can lift a freeze? Is there a record of them doing it?"

If lifting is a Slack message from whoever is senior enough, there is no record. In a
regulated buyer this is the question that creates urgency, because it is the one their
auditor asks.

## Establishing which sale this is

Two triggers, two different deals. Ask early.

> **"What made you look at this now?"**

- **An incident** — a deploy went out during a freeze and broke something. Fast, emotional,
  smaller. Demo the daily answer first: is there a freeze, does it apply to me.
- **Accountability** — an audit, a customer security review, a new compliance function, a
  post-incident action item. Slower, larger, annual. Demo the record first: the console and
  the audit trail.

Getting this backwards wastes the demo. An incident-driven buyer watching five minutes of
audit trail is bored; a compliance-driven buyer watching a Slack notification is unmoved.

## Qualification

Four conditions. All four, or expect a hard deal.

| | Ask | Disqualifying answer |
|---|---|---|
| **Multiple services** | "How many services would need to be in scope?" | One |
| **Multiple teams** | "How many teams deploy independently?" | One |
| **A real cadence** | "When was your last freeze? When's the next?" | "We don't really" |
| **Accountability** | "Who gets asked when something deploys during a freeze?" | Nobody / shrug |

**Say so when they fail.** Telling a two-person team that a Slack channel is genuinely
enough costs a deal that was never going to renew and buys credibility you will spend
later, when they are eight people. This is a product with an obvious lower bound — pretending
otherwise is detectable.

## Technical qualification

Ask before the demo, because the answers change what you show.

- **"Which CI system — and is it the same for every service?"** Mixed estates are the strong
  case: GitHub Actions, GitLab CI and Jenkins all run the same container. Single-pipeline
  shops may be better served by their pipeline's own freeze windows, and it is worth knowing
  that before they discover it.
- **"Who would add a step to the pipelines, and how long does that take?"** Often the real
  timeline. A platform team with a shared template is days; forty repositories owned by
  forty teams is a quarter.
- **"Where do release announcements go today?"** Slack means the notification lands where
  people already are. Email-only is a weaker start.
- **"Does anyone outside engineering need to see this?"** A yes usually means compliance,
  which means the audit trail and the accountability sale.

## Questions that decide the contract

Ask before proposing anything, not after.

- **"How many applications would you register?"** The price. Ask them to count rather than
  estimate — the catalog is visible in the product, so the number becomes checkable and
  the quote stops being negotiable on vibes.
- **"How long do you need to keep the record of what deployed?"** Retention is the paid
  lever, and a regulated buyer usually raises it unprompted.
- **"Does anything in your process require a vendor to hold a certification?"** Surfaces the
  SOC 2 requirement early, while qualifying out is still cheap.
- **"Who signs?"** The manager feels the pain and owns the budget line. If the answer is
  procurement plus security plus legal, the deal is a quarter, not a fortnight.

## What to do with a free-plan user

Not "how are you finding it?" — they will say fine.

> **"Who would need to approve moving to a plan that actually blocks?"**

The free plan announces; it cannot block. Every deploy through an active freeze on a free
organization prints a line in the engineer's own build log saying it would have been
refused on a paid plan. **That line is your inbound.** Ask whether anyone has seen it, and
who they mentioned it to.

## Before you promise anything

Re-read `01-positioning.md` §8. There is no public signup, nothing is deployed, demos run
locally, pilots are hand-provisioned and billing is invoiced. All of that is sayable. None
of it is improvisable.
