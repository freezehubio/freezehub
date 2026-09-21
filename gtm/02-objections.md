# Objections

Internal. Every answer below is checkable against the product or the documents, because a
technical buyer will check. Where the honest answer is "not yet", it says so — an objection
answered with a claim that fails diligence costs the deal twice.

Two rules before the list:

1. **Concede the true part first.** Most of these objections are partly right. A buyer who
   hears their point restated accurately stops arguing and starts evaluating.
2. **Never answer an objection with a roadmap promise.** "That's coming" is what a buyer
   hears as "no", and it is what a churned customer quotes back.

---

## "We already have a Slack channel for this."

**True part:** it works, right up until it doesn't, and for one team it is genuinely enough.

Slack answers *"is there a freeze on?"* badly — the answer is however far back someone is
willing to scroll — and it answers the other three questions not at all. It cannot tell an
engineer whether a freeze applies to *their* service, it cannot tell you whether anyone
deployed anyway, and it cannot tell you who lifted it.

The question that lands: **"After your last freeze, could you have produced a list of
everything that deployed during it?"** Nobody can. That is the gap, and it is not a
messaging gap.

If they are one team with one service, say Slack is fine and move on. You will be right,
and they will remember it when they are four teams.

---

## "You can't actually stop a deployment."

**Correct, and deliberate.** Do not soften this — `11-commercial.md` §1 flags that a
technical buyer will test the claim, and a product that claimed otherwise would fail the test.

Enforcement lives in the customer's pipeline: it calls the Policy API and decides what to
do with the answer. FreezeHub supplies the answer and the record that it was given.

The reframe: **a tool that could stop your deployments is a tool that can take your
production down.** You would be handing a third party a kill switch on your release process
and adding it as a hard dependency of every deploy. What FreezeHub does instead is answer a
question your pipeline asks — and the shipped connector fails closed, so the safe behaviour
is the default rather than something they have to remember to build.

---

## "Why wouldn't we just build this ourselves?"

**True part:** they could, and the first version is genuinely a weekend.

What they would build is questions 1 and 2 — a flag, an endpoint, a scope check. What
never gets built is 3 and 4: the deployment console and the audit trail. Not because
they are hard, but because nobody has ever been asked for them *before* the incident
review that asks for them.

Then ask what else is on the platform team's list this quarter, and whether this is
genuinely above it. Usually the internal version exists, half-finished, owned by someone
who left.

---

## "Per application is a strange way to price this."

**True part:** it is unusual, and unusual pricing deserves an explanation.

Give both exclusions, in this order — the reasoning is the answer:

- **Per seat would charge you for telling everybody.** Most engineers never sign in; the
  value arrives as a Slack message and an API answer. Charging per seat taxes the exact
  behaviour the product needs, and a freeze half the organization has not heard about is
  not a freeze. Users are unlimited on every plan.
- **Per evaluation would tax the check.** A customer optimising that bill calls the API
  less, which is precisely how a deployment slips through a freeze. Evaluations are
  unlimited, and that is a stated commitment.

Then the part that closes it: **you cannot game it downward.** An application left out of
the catalog has its deployments refused, so under-registering breaks your own pipeline.
The number you pay on is the number you actually run.

---

## "What happens if FreezeHub is down?"

The shipped connector **fails closed by default** — `FREEZEHUB_ON_ERROR=block`. If the
Policy API cannot be reached, the pipeline does not get an ALLOW.

Say plainly which way that cuts, because a good engineer will: it is the right default for
a gate and the wrong one for their uptime if the check is mandatory on every deploy. The
honest framing is that FreezeHub does not make this decision for them — `FREEZEHUB_ON_ERROR=allow`
is one environment variable, and the script says out loud in the build log which mode it
ran in.

The detail worth volunteering, because it answers the follow-up: **a setup problem is never
subject to that switch.** A missing API key or a typo'd URL always fails, deliberately —
otherwise enforcement could be turned off by breaking it, which is the failure mode they
are actually worried about.

Do not quote an availability number. There is no SLA below Enterprise and nothing has run
in production long enough to have a figure worth stating.

---

## "Where does our data live?"

`us-east-2`, in AWS. Say it directly; hedging on this question is worse than the answer.

For an EU buyer this is a real objection and currently a real gap — recorded internally,
with the trigger being exactly this conversation. What can be said truthfully: the backend
has no AWS coupling at all, so a second region is a deployment change rather than a
redesign. Do not promise a date.

What FreezeHub holds is narrow and worth saying: names a customer typed, the identity of
whoever deployed, and the audit trail. No source code, no credentials into their systems,
no repository access.

---

## "Who are we actually buying from, and how does tax work?"

Increasingly common from procurement, and the answer is good.

**Paddle is the merchant of record.** Paddle is the seller on the customer's receipt and
registers for and remits US sales tax and EU VAT itself. For a buyer this removes the
questions about a foreign supplier's tax registration in their jurisdiction.

Be accurate about timing: the rail is **decided and not yet built**. Early deals are
invoiced directly. Do not describe checkout as available.

---

## "Send us your SOC 2 report."

There isn't one, and there is no honest way around that.

What exists: the security design is documented and specific — tenant isolation resolved
server-side and never from client input, separate human and machine authentication,
credentials encrypted before they reach the database, raw API keys never stored.

Offer the written security brief and to answer their questionnaire directly. For a buyer
whose process hard-requires a report, qualify out early rather than spending a quarter on
it — and record who asked, because it is the clearest signal of when that work becomes
worth starting.

---

## "We're not sure we'd use it enough to justify it."

**True part:** if they freeze twice a year, they might be right.

Move off frequency and onto consequence: **what did the last deploy-during-a-freeze cost?**
Usually an incident, a rollback, and a meeting with people whose hourly cost is easy to add
up. The product is bought against that, not against the number of freezes.

If they genuinely freeze twice a year and nothing has ever gone wrong, the free plan is the
right answer and you should say so. It announces, it does not block, and it is the thing
that produces the build-log line the day it finally matters.

---

## "Can we get a discount?"

Not on a first deal, and the reason is defensible: every published price is a starting
position being tested against real deals, and discounting before there is a baseline
destroys the information the price exists to gather.

What to trade instead, in order: **annual commitment** (already priced — ten months for
twelve), a **longer pilot**, **onboarding help**, or a **reference agreement in exchange
for pilot pricing** — which is worth more than the discount right now, since there are no
references at all.
