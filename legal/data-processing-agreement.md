# Data Processing Agreement

Between **«RAZÓN SOCIAL»**, NIT «NIT», of «DOMICILIO», Colombia (the **Processor**), and the
customer organization identified in the order form (the **Controller**).

Under Colombian law this is the **contrato de transmisión** that Decreto 1377 Art. 25 requires
before personal data may be transmitted to an Encargado, including across a border. For a
customer's procurement team it is the document meant by *"send us your DPA"*. It is drafted to
satisfy both, and to align with GDPR Art. 28 where the Controller is subject to it.

> **Draft. Not legal advice, and not signable as it stands** — see §12, which states plainly
> what the service cannot yet do. Review by Colombian counsel is required before this is sent
> to anyone.

---

## 1. Roles

The Controller determines the purposes and means of processing the personal data it puts into
the service. The Processor processes it only to provide that service, on the Controller's
documented instructions, and for no purpose of its own.

Where the Processor collects data for its own purposes — a demo request, a billing identity,
its own operational logs — it acts as a **Responsable/controller in its own right**, outside
this agreement and under its published Política de Tratamiento.

## 2. Subject matter, duration, nature and purpose

**Subject matter.** Personal data contained in the Controller's use of FreezeHub: its users'
accounts, its audit records, its deployment checks, its catalog, its integrations and its
notifications.

**Duration.** For as long as the Controller's subscription is in effect, plus the period in
§9.

**Nature and purpose.** Storing, organising, retrieving and transmitting the data in order to
operate a deployment-restriction service: recording restrictions, answering policy checks from
the Controller's CI systems, notifying the channels it configures, and keeping an audit record
of changes.

## 3. Categories of data and of data subjects

| Category | Data |
|---|---|
| **Identity** | Name, email address, identity-provider subject identifier, organization role |
| **Activity attribution** | The actor recorded against an audit event or a deployment check |
| **Customer content** | Free text the Controller's users write — restriction names, reasons, descriptions — which **may incidentally contain personal data** |
| **Integration secrets** | Webhook URLs, signing secrets, channel configuration; an email destination is personal data |

**Data subjects:** the Controller's own personnel who hold accounts, and any individual named
incidentally in free text.

No special-category data is required by the service, and the Controller is asked not to place
it in free text. The service is not directed at children.

## 4. The Processor's obligations

1. **Process only on documented instructions**, including for international transfers. This
   agreement and the Controller's use of the product are those instructions. If the Processor
   believes an instruction breaches applicable law, it will say so.
2. **Confidentiality.** Personnel with access are bound by confidentiality obligations and are
   named rather than anonymous; access is reviewed periodically.
3. **Security.** The measures in Annex B, which are the measures actually implemented.
4. **Subprocessors.** §5.
5. **Assistance with data subject rights.** §6.
6. **Breach notification.** §7.
7. **Deletion or return.** §9, read together with §12.
8. **Information and audit.** §10.

## 5. Subprocessors

The Controller gives general authorization for the Processor to engage subprocessors. The
current list, and what each receives, is published at `legal/subprocessors.md` and maintained
there.

The Processor will give the Controller **at least 30 days' notice** before adding or replacing
a subprocessor, during which the Controller may object on reasonable data protection grounds.
If the objection cannot be resolved, the Controller may terminate the affected part of the
subscription without penalty.

Each subprocessor is bound by obligations no less protective than these.

## 6. Data subject rights

Requests reaching the Processor about the Controller's data are **forwarded to the Controller
and not answered by the Processor**, which has no authority to decide them. The Processor
notifies the Controller without undue delay and assists it in responding, taking into account
the nature of the processing.

**Deadlines the Controller should be aware of.** Where Colombian law applies, a *consulta* must
be answered within 10 business days (extendable by 5) and a *reclamo* within 15 business days
(extendable by 8). The Processor's assistance is scheduled to fit inside those, which is
shorter than the GDPR's month.

## 7. Personal data breach

The Processor notifies the Controller **without undue delay** after becoming aware of a
personal data breach affecting the Controller's data, with the information it has at the time,
supplemented as the assessment continues. It does not wait for a complete picture before the
first notification.

The Processor maintains a register of incidents **whether or not they were notifiable**.

## 8. International transfer

The service runs in **AWS `us-east-2` (United States)**. Under Colombian law this is a
*transmisión* to an Encargado, not a *transferencia* to another Responsable, and this
agreement is the contract Art. 25 requires for it.

Where the Controller is subject to the GDPR, the parties will put in place the Standard
Contractual Clauses or another Art. 46 mechanism as an addendum. **No such addendum is
attached to this draft.**

## 9. Deletion and return

On termination, at the Controller's election:

- the Controller may **export** its data during a 30-day grace period following termination;
- after that period the Processor **deletes** the Controller's personal data from live systems,
  including the identities held in the identity provider and the encrypted integration
  secrets;
- copies in backups are deleted as their retention period expires, and any backup restored
  after a deletion has that deletion re-applied to it;
- **records the Processor must keep by law** — accounting and tax documents — are retained for
  the statutory period and are not deleted on request. This is stated rather than omitted:
  "we delete everything" is a promise the tax code does not allow.

Free text is **not searched** for incidental personal data on deletion. Scanning a restriction
reason for a named individual is neither feasible nor expected, and the limit belongs in
writing.

## 10. Information and audit

The Processor makes available the information necessary to demonstrate compliance with this
agreement, and allows for and contributes to audits by the Controller or an auditor it
mandates, on reasonable notice, no more than once a year unless a breach has occurred, subject
to confidentiality and to not compromising other customers' security.

**There is no SOC 2 report, ISO 27001 certificate or equivalent third-party attestation**, and
none is implied by this clause. Nothing has been running long enough to produce evidence for
one.

## 11. Order of precedence

This agreement prevails over conflicting terms in the subscription agreement on the subject of
personal data. Where applicable law imposes a stricter obligation, that obligation applies.

---

## 12. What the service cannot do today

**This section is why this draft is not signable, and it is placed in the agreement rather
than in a covering note so it cannot be skipped.**

`OI-36` records that **nothing can be forgotten**. There is today no path to delete a user, no
path to delete an organization, no export of an organization's data, and no pseudonymization
of an identity on erasure. `docs/17-data-protection.md` §5 P-3 states it directly: on
cancellation, *"today nothing happens — there is no organization deletion anywhere."*

So **§9 describes an obligation the system cannot currently perform**, and §6's assistance
would today be manual work against the database.

Signing this as drafted would be a contractual promise the product cannot keep. Three ways
forward, and the choice is the operator's:

1. **Build `OI-36` first**, then sign §9 as written. The honest option, and the slowest.
2. **Sign with §9 amended** to what is true today — export and deletion performed manually by
   an operator within a stated period — and replace the clause when `OI-36` lands. Truthful,
   and visibly weaker than what a customer will expect.
3. **Do not offer a DPA yet.** Which, per `gtm/02-objections.md`, loses the deals where
   procurement asks for one — which is most of them above a certain size.

Whichever is chosen, **this section is deleted before the document is sent**, and what it
describes must be resolved rather than quietly dropped.

---

## Annex A — Processing details

Set out in §2 and §3.

## Annex B — Technical and organisational measures

These are the measures the system actually implements, taken from `docs/17-data-protection.md`
and `docs/06-security.md`. Nothing aspirational is listed.

| | Measure |
|---|---|
| **Encryption in transit** | TLS to the service and to every outbound destination |
| **Encryption at rest** | Encrypted volumes; integration configuration and signing secrets additionally encrypted with AES-256-GCM at the application layer |
| **Secret storage** | API keys stored only as a SHA-256 hash; the raw key is never persisted. Database password and encryption key held in AWS SSM Parameter Store and written to the host at `0600` |
| **Tenant isolation** | Every tenant-owned resource is scoped by organization, and an `organizationId` supplied by a client is never trusted as authorization |
| **Access control** | Administrator and member roles distinguished on the server; management endpoints administrator-only |
| **Operator access** | No SSH and no open port 22; access is by AWS SSM Session Manager, authorised by IAM and recorded in CloudTrail. Deploys are IAM-authorised API calls with short-lived credentials, never stored keys |
| **Audit** | Changes to restrictions and catalog recorded with actor and timestamp |
| **Outbound request control** | Customer-supplied webhook destinations are resolved and every resolved address checked on each request, so a destination cannot be pointed at internal infrastructure |
| **Rate limiting** | Applied to authenticated and unauthenticated endpoints, counted per API key so that the defence cannot become the outage |
| **Vulnerability management** | Dependency and container scanning on every change, gated at HIGH |
| **Backups** | Nightly database dump to object storage |

**Two limits stated rather than omitted.** The outbound address check does not close a DNS
rebind between its resolution and the client's; the durable answer is network egress control,
which is not yet in place (`OI-23`). The backup restore has not been drilled end to end
(`FZ-155`).

## Annex C — Subprocessors

See `legal/subprocessors.md`.
