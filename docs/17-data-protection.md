# FreezeHub — Data Protection

## Purpose

What personal data FreezeHub holds, who is legally answerable for each piece of it, how long
it is kept, and what happens when someone asks for it back.

Written against the schema, the deployment and the controllers as they actually are, not as
any other document describes them. Where a claim here disagrees with the code, the code is
right and this document is a defect.

**The operating company is Colombian**, so Ley 1581 de 2012 and Decreto 1377 de 2013 apply.
GDPR mechanics are specified alongside them deliberately: `POST /api/signup` is self-serve by
design, and a product anyone can sign up for does not get to choose where its data subjects
live.

## 1. Two roles, and the whole document turns on the difference

FreezeHub is not one thing legally.

**Encargado** — for data a customer puts into the product. `users`, `audit_event`,
`deployment_check`, the catalog and restriction tables, `integration`, `notification`. The
customer organization decides why these exist; FreezeHub acts on its instructions and uses
none of it for its own purposes. A subject request arriving here is **forwarded to that
organization**, not answered.

**Responsable** — for data FreezeHub collects for itself. `demo_request`, the signup record
before an organization exists, billing identifiers, and operational logs. FreezeHub needs its
own lawful basis and its own retention decision for each, and **answers the titular directly**.

`demo_request` is the sharp edge: the one table with no `organization_id` (`03-data-model.md`
flags it as the shape a tenant-isolation bug would take), and the only place FreezeHub holds
personal data about someone who never became a customer.

## 2. Classes

**P1 identity** · **P2 customer content** (free text; may incidentally contain personal data)
· **P3 secret material** · **P4 operational**

## 3. The map

`Today` is what the code and `infra/singlebox/` actually do.

| Store | Class | Role | Today | Should be | On erasure |
|---|---|---|---|---|---|
| `users.email`, `.cognito_subject` | P1 | encargado | **no delete path exists** | org life + 30d | hard delete + Cognito identity |
| Cognito user pool | P1 | encargado | **no delete path** | org life + 30d | `AdminDeleteUser` |
| `audit_event.actor_label` | P1 | encargado | unlimited, sold as a feature | event unlimited, *identifier* not | pseudonymize in place |
| `audit_event.details` | P2 | encargado | unlimited | unlimited | out of scope — §8 |
| `deployment_check.actor` | P1 | encargado | 7–3650d, enforced since `FZ-114` | unchanged | pseudonymize in place |
| `deployment_check.reference`, `.source` | P2 | encargado | same window | unchanged | retained |
| `demo_request` | P1+P2 | **responsable** | **no rule, no delete path** | 180d unless converted | hard delete |
| `subscription` billing ids | P4→P1 at the processor | **responsable** | no rule | statutory invoice period | **cannot erase** |
| `integration.config` | P3, +P1 when `EMAIL` | encargado | AES-256-GCM, integration life | unchanged | deleted with integration |
| `integration.signing_secret` | P3 | encargado | AES-256-GCM | unchanged | deleted with integration |
| `api_key.token_hash` | P3 | encargado | SHA-256, raw never stored | unchanged | not personal data |
| `notification.last_error` | P4 | encargado | **unbounded TEXT, no rule** | bounded and truncated | — |
| Application logs | P4 | responsable | no personal data by rule | unchanged | — |
| PostgreSQL volume | all | both | encrypted EBS, not published to host | unchanged | — |
| Nightly `pg_dump` to S3 | all | both | **`FZ-155` — restore never drilled** | lifecycle expiry, drilled | lags by the expiry window |

### Four things the map surfaces

**`notification.last_error` is unbounded TEXT holding a failed delivery's error.** If that
error ever carries a response body from a customer's webhook endpoint, arbitrary third-party
content lands in a column nobody classified and nothing purges.

**Billing records cannot be erased, and that is correct.** Statutory retention of accounting
documents overrides the request. Say so explicitly — "we delete everything" is a promise the
tax code will not let you keep.

**Backups defeat erasure for as long as they are kept**, and here they are `pg_dump` files in
S3 whose restore has never been drilled (`FZ-155`). An untested backup is the classic way to
discover there was none; an untested *restore* is also how an erasure claim turns out to be
untrue. The honest statement is that erasure completes in live systems immediately and in
backups within the lifecycle window, and a restored dump is re-scrubbed.

**Free text is never searched on erasure.** A restriction's `reason` can say "delaying
because Dana is out". Scanning free text for a named individual is neither feasible nor
expected — but the limitation belongs in writing rather than being discovered.

## 4. Autorización — and why the GDPR reasoning does not transfer

Ley 1581 Art. 9 makes **autorización previa, expresa e informada** the general basis, with
only the narrow Art. 10 exceptions. There is no broad legitimate-interest basis to lean on.

Art. 8(b) then gives the titular the right to **solicitar prueba de la autorización**. So the
authorization has to be *stored*: the timestamp, and the exact text shown at the moment it was
given. An authorization that cannot be proven cannot be relied on.

**This is not currently possible.** `demo_request` has no column for either, and the demo form
has no such control. A checkbox alone would not close it — see `OI-37`.

## 5. Procedures

| | Event | What happens |
|---|---|---|
| **P-1** | Someone asks what is held about them | Route first: a customer's user → encargado → forward to that organization. A prospect or billing contact → responsable → answer directly. **Consulta: 10 días hábiles, prorrogable 5.** |
| **P-2** | Someone asks to be erased | Encargado: do not act unilaterally; act on the responsable's instruction. **One random token per erasure**, applied to every row naming the person, mapping stored nowhere. `users` and the Cognito identity hard-deleted; `audit_event.actor_label` and `deployment_check.actor` overwritten. The event survives, the attribution does not. **Reclamo: 15 días hábiles, prorrogable 8.** |
| **P-3** | An organization cancels | Today nothing happens — **there is no organization deletion anywhere.** Should be: `CANCELLED` → 30-day grace with export available → hard delete in foreign-key order, every Cognito identity, every integration secret. Keep one operational record holding no personal data. |
| **P-4** | A prospect goes cold | `demo_request` older than 180 days and not converted → purged. Converted ones follow P-3. Follows the existing unverified-organization purge. |
| **P-5** | Retention runs | Daily, one window per class, **emitting a count per class**. Silence is the failure mode that let a purge never execute once for as long as the application had existed (`FZ-114`). |
| **P-6** | A secret leaks | API key → `revoked_at`, never cleared. Signing secret → rotate. Encryption key → re-encrypt everything, **which cannot currently be staged** (§7). |
| **P-7** | A breach | Detect → contain → assess within 24h. As encargado, notify every affected responsable without delay. As responsable, notify the SIC. **Record every incident in a register regardless of notifiability** — "we had none" is not evidence. |
| **P-8** | An operator touches production | Named operators, secrets from SSM Parameter Store, access reviewed quarterly. **`provision-organization.sh` writes no audit event** (`OI-39`). |

## 6. Transmisión internacional

The deployment runs in **AWS `us-east-1`** (`FZ-141`, `D-35`). Colombian law distinguishes
**transferencia** (to another responsable) from **transmisión** (to an encargado processing on
the responsable's behalf). This is a transmisión, governed by Decreto 1377 Art. 25, and the
route is a **contrato de transmisión** with clauses on scope, purposes, security and
confidentiality — not an adequacy finding.

Encargados receiving personal data:

| Encargado | What it receives |
|---|---|
| Amazon Web Services | everything in §3 |
| Paddle (merchant of record, `D-34`) | billing identity and payment data |
| Transactional email sender | recipient address |
| Slack | only where a customer configures that channel |

**Paddle is the seller on the customer's receipt** and remits US sales tax and EU VAT itself.
It is decided and not yet built (`FZ-149`); early deals are invoiced.

## 7. Known gaps

None of these has a story yet. They are recorded in `09-open-issues.md`, which is where a gap
with no owner belongs — naming a story ID before the story exists is how this section got its
first version wrong, and the IDs it invented were claimed within a day by unrelated work.

| | Gap | Issue |
|---|---|---|
| 1 | **Nothing can be forgotten.** No way to delete a user, delete an organization, export an organization's data, or pseudonymize an identity on erasure | `OI-36` |
| 2 | No retention rule for `demo_request`, and no stored autorización to prove under Art. 8(b) | `OI-37` |
| 3 | Encrypted values carry `fzenc1:` — a *format* version, not a **key identifier** — so two keys cannot be live at once and rotation cannot be staged | `OI-38` |
| 4 | `provision-organization.sh` writes tenant rows and no audit event | `OI-39` |
| 5 | `notification.last_error` is unbounded `TEXT` | `OI-40` |
| 6 | No DPA, subprocessor list, privacy notice or Política de Tratamiento | `OI-41` |
| 7 | No breach register | `OI-42` |

## 8. What this does not cover

- **Free text is not searched on erasure.** `reason`, `description` and `audit_event.details`
  may contain an incidental name.
- **Billing records are not erasable**, by design and by law.
- **Backups lag live erasure** by the S3 lifecycle window, and the restore has never been
  drilled (`FZ-155`).
- **No risk register, business continuity plan or vendor management policy.** Those describe a
  company, not a codebase.
- **Legal wording is not drafted here.** The Política de Tratamiento, the DPA and the privacy
  notice take their structure from this document and their language from a lawyer (`OI-41`).
- **No SOC 2 report exists**, and nothing has run long enough to produce evidence for one.
