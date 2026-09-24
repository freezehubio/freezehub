# Subprocessors

Who else receives personal data from FreezeHub, and what each one receives. Referenced by
`legal/data-processing-agreement.md` §5, which commits to **30 days' notice** before this list
changes.

**Last updated:** «FECHA DE ENTRADA EN VIGENCIA»

## Current

| Subprocessor | Role | What it receives | Where |
|---|---|---|---|
| **Amazon Web Services, Inc.** | Hosting, storage, backups, identity directory, email delivery where used | Everything the service holds: accounts, audit records, deployment checks, catalog, integrations, notifications, and the nightly database dump | `us-east-2`, United States |
| **GitHub, Inc.** | Container registry for the connector image | No personal data. Listed because the image is part of the delivered product, not because it receives data | United States |

## Decided, not yet in use

| Subprocessor | Role | What it will receive | Status |
|---|---|---|---|
| **Paddle.com Market Ltd** | Merchant of record (`D-34`) — Paddle is the seller on the customer's receipt and remits sales tax and VAT itself | Billing identity and payment data. Card details go to Paddle and never reach FreezeHub | **Not integrated.** `FZ-149` is the story and it is not scheduled; early deals are invoiced directly |

## Only where a customer configures it

| Destination | What it receives | Note |
|---|---|---|
| **Slack** | The contents of freeze notifications sent to a channel the customer configures | Configured by the customer for its own workspace. The customer chooses this destination; FreezeHub does not send there by default |
| **Any webhook endpoint the customer sets** | The notification payload | The destination is the customer's own choice and its own responsibility. Outbound requests are checked so a destination cannot be aimed at internal infrastructure (`OI-23`) |

## Notes

**Transactional email.** Recipient addresses reach whatever sender is configured. Today that
is AWS, listed above. If a third-party sender is ever introduced it is an addition to this
list and triggers the 30-day notice.

**This list is the one that is published.** `docs/17-data-protection.md` §6 holds the same
information for internal use; if the two ever disagree, that document is written against the
deployment and is the one to trust — and the disagreement is a defect to fix here.
