# FreezeHub — Go-to-Market

Sales and customer-facing material. This directory is **not a specification**: nothing here
constrains implementation, and where it disagrees with `docs/`, `docs/` is right and a file
here needs fixing.

It is kept out of `docs/` deliberately. The numbered documents are the source of truth for
what gets built; these are the source of truth for what gets said, and confusing the two
produces a roadmap driven by a pitch deck.

## What is here

| | For | State |
|---|---|---|
| `01-positioning.md` | internal | the foundation — everything else derives from it |
| `02-objections.md` | internal | what to say when a buyer pushes back |
| `03-discovery.md` | internal | what to ask, and who to walk away from |

Internal means *do not hand it to a customer*. These name what the product cannot do yet,
which is essential for selling honestly and unhelpful read over someone's shoulder.

Customer-facing pieces — one-pager, business case, technical overview, security brief,
FAQ — are separate stories and land in `gtm/customer/`. Contract documents land in
`gtm/legal/`.

## The rule this directory runs on

**No claim that is not true today.** No customer logos, no testimonials, no case studies,
no usage counts, no "trusted by". FreezeHub has no customers yet, and a technical buyer
tests claims — `11-commercial.md` §1 makes the same point about the enforcement claim.
Inventing social proof to fill a slide is how the first real deal dies in diligence.

Where something is genuinely coming, say when, and say it is coming. Where it does not
exist, the honest sentence is usually stronger: *"we cannot stop your deployment, and we
do not try to"* has closed more technical buyers than any claim to the contrary would.

## Source material

Read before changing anything here. These are load-bearing:

- `docs/00-product.md` — actors and the MVP capability boundary
- `docs/11-commercial.md` — pricing metric, plans, free-tier logic, GTM motion
- `docs/10-demo.md` — the demo script, and why its arc is ordered the way it is
- `docs/12-connectors.md` — what integrating actually involves
- `docs/07-decisions.md` — `D-14`, `D-19`, `D-21`, `D-33`, `D-34` all carry commercial weight
