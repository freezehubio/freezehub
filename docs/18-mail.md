# FreezeHub — Mail

## Purpose

Which addresses exist on `freezehub.io`, which are mailboxes and which are aliases, and why
the DNS says what it says. Written when the estate was created (`FZ-215`), because every
value below is load-bearing and at least two of them look wrong to somebody tidying up.

## 1. Two mailboxes, everything else an alias

The plan allows two real mailboxes. They are split on **identity versus role**, not on
people versus machines:

| | |
|---|---|
| `camilo@freezehub.io` | The human. Outbound sales, replies, LinkedIn, vendor account recovery. Cold mail has to come from a person or it is not answered, and account recovery should be bound to the one operator. |
| `hola@freezehub.io` | The company. Every address published anywhere is an alias into it. |

Aliases into `hola@`:

```text
dmarc@        the aggregate reports the record below asks for
privacidad@   statutory; see §4
security@     questionnaires, security.txt, researchers (RFC 2142)
support@      customers whose deploys have stopped — never routine, because the gate fails closed
facturacion@  hand invoices now, the payment rail later
postmaster@   abuse@      deliverability hygiene
```

The split survives a second person: they get `hola@`, not an identity. It also keeps
`privacidad@` a monitored place rather than a filter in a sales inbox, which is the
difference between answering a data-rights request and missing a legal deadline.

## 2. The DNS, and the two values that look wrong

Mail is GoDaddy Professional Email; **DNS is Route 53**, in the project account
(`16-accounts.md`), zone `Z05328562QOD2HA2DHF83`. Those are different vendors and the
instructions GoDaddy prints assume it hosts both.

Terraform does not manage these records. It takes `hosted_zone_id` as a variable and owns
only the records it declares (`app`, `api`, certificate validation), so hand-made mail
records are invisible to it and survive `terraform apply`.

**The apex SPF ends in `-all`, and that is deliberate.** A hard fail is the point. The
consequence to remember is in §3.

**DMARC is parked at `p=none`, which is *not* what GoDaddy generates.** Their record is
`p=reject` with the reports going only to their own address. On a domain that had never
sent mail, that throws away anything misaligned before anybody can see it failing, and
sends the evidence somewhere unreadable. The record here keeps both destinations and starts
at `p=none` on purpose:

```text
v=DMARC1; p=none; rua=mailto:dmarc@freezehub.io,mailto:dmarc_rua@onsecureserver.net; adkim=r; aspf=r;
```

**Tighten it deliberately, not on a schedule**: read the aggregate reports until nothing
legitimate is failing, then `quarantine`, then `reject`. Anything sending as this domain —
the product's notifications, a future marketing tool — has to be aligned *before* the step
that starts discarding mail, not after.

## 3. When SES arrives, do not touch the SPF record

`application.yml` deliberately declares no `freezehub.notifications.email.from`; the sender
is intended to be `notificaciones@freezehub.io`, send-only, with **no route to a human** —
an out-of-office answering a freeze announcement should die at the boundary.

It needs no mailbox. `infra/README.md` §4 records the mechanism: a verified SES identity,
with the task role permitted to send.

**The wrong way to authorise it is `include:amazonses.com` on the apex SPF**, which would
authorise SES's entire shared pool to send as this domain. Use a **custom MAIL FROM
subdomain** (`mail.freezehub.io`) with its own SPF, and let SES's DKIM CNAMEs carry DMARC
alignment. The apex record stays GoDaddy-only.

## 4. `privacidad@` is the one with a clock

`legal/README.md` requires it to **exist and be monitored before the privacy policy is
published**, because the statutory terms start when a request arrives, not when somebody
notices it. It is also the only one of that document's ten placeholders that can be
satisfied without a company existing.

## 5. Verifying, and the check `dig` cannot do

```bash
dig +short TXT freezehub.io                              # verification token + SPF
dig +short MX freezehub.io
dig +short TXT _dmarc.freezehub.io
dig +short TXT secureserver1._domainkey.freezehub.io     # follows the CNAME to the key
```

The last one matters: a `CNAME` lookup proves the chain resolves, while the `TXT` proves
there is a `v=DKIM1` key at the end of it. Both were confirmed when this was set up.

**Neither proves mail is signed.** Only a delivered message does: send to a Gmail address,
open *Show original*, and require `SPF: PASS`, `DKIM: PASS`, `DMARC: PASS`. Do that before
any volume — a domain with no sending history and a broken signature lands in spam, and the
copy gets blamed for it.
