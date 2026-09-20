# Positioning

Internal. The source every other piece of go-to-market material derives from. If a
one-pager, a deck or an email says something this document does not, one of the two is
wrong and it is usually not this one.

## 1. What is actually being sold

**Not software that stops deployments.** FreezeHub cannot stop a deployment and does not
try to. Enforcement lives in the customer's pipeline, which calls the Policy API and
decides what to do with the answer.

What is sold is **the authoritative answer, and the record that it was given.**

| The customer's question | What they use today | What FreezeHub replaces it with |
|---|---|---|
| Is there a freeze on right now? | Slack scrollback, a calendar invite, asking someone | One place, one answer |
| Does it apply to *my* service? | Reading a paragraph and guessing | Scope evaluated by the product |
| Did anyone deploy during it? | Nobody can answer | The deployment console |
| Who lifted the freeze, and when? | Nobody can answer | The audit trail |

**The first two make it used daily. The last two make it renewed.** That sentence is the
whole strategy. Lead a demo with the first two, because they are the felt pain; close on
the last two, because they are what a Slack channel can never do and what nobody discovers
they needed until the review meeting.

## 2. The category problem

FreezeHub has no category. Nobody searches for "deployment freeze management", so there is
no keyword to win and no quadrant to appear in. This shapes everything:

- **Lead with the situation, not the category.** "The week before your release, how does
  an engineer find out whether they can deploy?" lands. "We're a deployment freeze
  management platform" does not.
- **The competitor is a habit, not a product.** See §5.
- **Outbound beats inbound** until the situation is recognisable enough to search for.

Do not spend money teaching the market a category name. Spend it finding the teams that
already feel this and have given it their own name — "release lockdown", "change freeze",
"code freeze", "peak moratorium", "the golden hour".

## 3. Who buys

`00-product.md` names four actors. Only one of them signs.

| Actor | Role in the deal |
|---|---|
| **Release / Engineering Manager** | **The buyer.** Feels the pain, owns the budget line, and is accountable when a deploy slips through. |
| **Organization Administrator** | The implementer. Usually the same person at small scale, a platform engineer above it. |
| **Engineer** | The daily user and the loudest voice — for or against. Never the buyer. |
| **CI/CD System** | The integration. Its owner has a veto. |

`11-commercial.md` §2 puts it precisely: the buyer for a compliance-adjacent tool is
*usually a manager who wants a conversation*, and it comes out of a manager's budget
rather than a platform-team line item. Price accordingly and sell accordingly — this is
not a bottom-up developer-tool motion, even though developers use it daily.

**Two buying triggers, and they are different sales.**

- **Operational trigger** — a deploy went out during a freeze and caused an incident. Fast,
  emotional, small. Sell the daily answer.
- **Accountability trigger** — an audit, a customer security review, a new compliance
  function, a post-incident action item. Slower, larger, annual contract. Sell the record.

Ask which one you are in within the first ten minutes. The demo order changes.

## 4. Qualification in one line

**Multiple services, multiple teams, a real freeze cadence, and somebody accountable when
it goes wrong.**

Drop any of the four and the deal gets hard:

- One service or one team — a Slack message genuinely works, and saying so buys credibility
  you will spend later.
- No freeze practice — you are selling a process change, not a tool. Much longer, rarely worth it.
- Nobody accountable — there is no renewal, because nobody notices the value.

## 5. Who you are actually against

Almost never another product.

| Alternative | Why it loses | Why it sometimes wins |
|---|---|---|
| **A Slack channel** — the real incumbent | Answers question 1 badly and 2–4 not at all. Scrollback is not a record. | Free, already there, and nobody has been burned yet |
| **A calendar invite** | Nobody's pipeline reads a calendar | Everyone already has one |
| **A Confluence page / spreadsheet** | Goes stale the first time someone forgets | Feels like governance |
| **A flag in CI, homegrown** | Per-pipeline, no scope model, no audit trail, and it is someone's side project | Already built, and sunk cost is persuasive |
| **ITSM change management** (ServiceNow, Jira) | Heavyweight, ticket-shaped, engineers route around it | Already bought, and the compliance function trusts it |
| **Pipeline-native freeze windows** (Harness, Spinnaker) | Tied to one pipeline; useless in a mixed estate | If they are genuinely single-pipeline, this is a fair fight |

**The wedge is the mixed estate plus the record.** FreezeHub is pipeline-agnostic — GitHub
Actions, GitLab CI and Jenkins all run the same container — and it produces an audit trail
nobody else in that list produces. A company with one pipeline and no auditor is a weak
prospect; a company with three pipelines and an auditor is the whole thesis.

## 6. Pricing, and why it disarms objections

Price scales with **registered applications**. Users, teams, environments, restrictions and
policy evaluations are unlimited on every plan, deliberately.

Both exclusions are selling points, not generosity, and both have an argument worth
delivering out loud:

- **Not per seat.** Most engineers never sign in — value arrives as a Slack message and an
  API answer inside a pipeline. Worse, seats would charge the customer for the exact thing
  the product needs them to do: tell *everybody*. A freeze half the organization has not
  heard about is not a freeze.
- **Not per evaluation.** Metering the check taxes the behaviour the product depends on. A
  customer optimising their bill would call the API less, which is precisely how a
  deployment slips through a freeze.

**Applications cannot be gamed**, and this is worth saying plainly: leaving an application
out of the catalog to save money breaks that application's pipeline, because an
unregistered application is refused. The incentive points the right way — registering
everything is both what the customer wants and what FreezeHub is paid for.

Every published price is a starting position, not a finding. Move them when real deals say so.

## 7. The free plan is a sales instrument

The line between Free and paid is **one capability**: a free organization cannot create a
blocking freeze. Its restrictions are advisory.

The mechanism is the point. Every deploy through a free organization's active freeze prints
a line in the engineer's own build log saying this deployment *would have been refused on a
paid plan*. That fires on the person who feels the pain and cannot sign — which is how it
reaches the person who can.

So in a conversation with a free-plan user, the qualifying question is not "how are you
finding it?" It is **"who would need to approve moving to a plan that actually blocks?"**

## 8. What you cannot say yet

Read this before every customer conversation. It changes as stories land — check the
backlog rather than trusting this list.

- **There is no public site and no self-serve signup.** `FZ-082` and `FZ-111` are TODO.
  Never say "start a free trial" or send someone to a URL to sign up. There isn't one.
- **Nothing is deployed.** The deployment is designed and written but has never been
  applied. Demos run locally, from `docs/10-demo.md`, against a seeded database.
- **Pilots are hand-provisioned** by an operator running a script against the database.
  That is fine to do and fine to say — "we'll set you up" is a normal enterprise sentence —
  but it is not self-service and must not be described as such.
- **No customers, so no references.** When asked, say so. "You would be the first" is a
  real position with real leverage in a pilot negotiation, and it is checkable.
- **Billing is not live.** The payment rail is decided — Paddle, as merchant of record —
  but unbuilt. Early deals are invoiced.

The gap between §1 and §8 is not a reason to delay selling. Design partners are found
before the product is finished, and the demo is real. It is a reason never to improvise.
