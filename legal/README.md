# Legal instruments

The four documents `OI-41` named. They take their **structure and substance** from
`docs/17-data-protection.md`, which is written against the schema and the deployment as they
actually are. Where a claim here disagrees with that document, that document is right and this
is a defect; where it disagrees with the code, the code is right and both are defects.

| File | What it is | Who reads it |
|---|---|---|
| `politica-de-tratamiento.md` | **Política de Tratamiento de Datos Personales**. Statutory under Decreto 1377 Art. 13 — not an optional courtesy | Any *titular*; the SIC, if it ever asks |
| `aviso-de-privacidad.md` | **Aviso de Privacidad**, Decreto 1377 Art. 14. The short form shown at the moment data is collected, when showing the whole Política is not practical | Anyone using the signup or demo form |
| `data-processing-agreement.md` | The DPA. For Colombian law it is the **contrato de transmisión** Art. 25 requires; for a customer's procurement team it is the document they mean when they say "send us your DPA" | Customers, before they sign |
| `aviso-prospeccion.md` | The **Art. 12** disclosure for a cold outreach email footer. Not the Aviso: there is no collection moment, so the first message is where the duty to inform is discharged (`FZ-223`) | Anyone receiving unsolicited outreach |
| `subprocessors.md` | Who else receives personal data, and what each one gets | Customers; referenced by the DPA |

## Read this before publishing any of them

**These are drafts, and they are not legal advice.** I am not a lawyer and neither is this
repository. `docs/17-data-protection.md` §8 already stated the position these were written
against: the documents *take their structure from that document and their language from a
lawyer*. What is here is the structure, filled in with the facts of the system — which is the
part a lawyer would otherwise have to extract from the codebase — and it needs review by
Colombian counsel before it is published or sent to a customer.

**Nothing here may be published while a guillemet placeholder remains.** They mark facts only the
company knows, and several are required by statute: a Política missing Art. 13's identifying
details is not a deficient Política, it is not one.

```bash
node legal/check-placeholders.js          # lists what is still unfilled
node legal/check-placeholders.js --strict # exits non-zero if any remain
```

## What must be filled in, and where to get it

**Six of the ten were filled by `FZ-221`** from facts the operator supplied: the physical
address, the city, the telephone, the privacy mailbox, the responsible area and the public
URL of the Política. The table below is kept because it says what each field *is*, which is
what matters the next time one of them changes.

**One remains.** `FZ-223` filled the razón social and resolved the NIT, which turned out not
to be a missing fact at all — see below.

| Still open | Why it cannot be filled from this repository |
|---|---|
| `«FECHA DE ENTRADA EN VIGENCIA»` | The day it is published, which has not happened |

**`«VIGENCIA DE LAS BASES DE DATOS»` was filled by `FZ-223`, and the document settled it, not
a preference.** § 2.1 already said *180 días si no se convierte en cliente*, so the two-year
period that had been proposed would have contradicted the Política’s own purposes table. It
now states the § 2.1 periods by reference. No delete path exists yet either way (`OI-36`).

### FreezeHub is a persona natural, and that settles three of the rows below

Confirmed with the operator (`FZ-223`): there is no company. FreezeHub is operated by a
**persona natural**, so four rows in the table below name a source that does not exist — a
*certificado de existencia y representación legal* is issued for a sociedad and there is none.

**The razón social is the operator’s own name.** A persona natural does have one, and it is
their name as it appears on the cédula — not the words "persona natural", which are a legal
category rather than a name. **FreezeHub is the *nombre comercial*,** which is optional and
protected by actual use rather than by registration. Both may be used; they are not
interchangeable.

**The NIT is deliberately absent from every public document, and must stay absent.**
Decreto 1377 Art. 13(1) requires exactly *nombre o razón social, domicilio, dirección, correo
electrónico y teléfono*. **No tax or identity number appears anywhere in Art. 13.** For a
persona natural the NIT is the cédula, so publishing it would put a national identity number
on an indexed page to satisfy an obligation that does not exist — and full name plus cédula
plus home address is the combination used for *suplantación de identidad*. It is carried only
in `data-processing-agreement.md`, which is handed to a counterparty under contract and never
published, where identifying a party by tax ID is ordinary.

**The address cannot be dropped the same way:** Art. 13(1) does require `domicilio` and
`dirección`. If the published address is a home, a coworking or accountant’s address
satisfies the same requirement with less exposure. That is a decision, not a defect.


| Placeholder | What it is | Where it comes from |
|---|---|---|
| `«RAZÓN SOCIAL»` | **Filled.** For a persona natural, their own name as on the cédula | The cédula. Not a certificado — there is no sociedad |
| `«NIT»` | **Not required in public documents** (Art. 13 omits it). In the DPA only, as cédula + DIAN verification digit | The RUT, which states it authoritatively |
| `«DOMICILIO»` | Registered city. Required by Art. 13(1) | The operator |
| `«DIRECCIÓN FÍSICA»` | A physical address. Art. 13(1) requires one; a coworking or agent address is still an address, and is worth preferring over a home |
| `«TELÉFONO»` | A telephone number. Also required by Art. 13(a) | The company |
| `«CORREO DE PRIVACIDAD»` | The address petitions, consultas and reclamos arrive at. **It must exist and be monitored before this is published** — the statutory clocks below start when a request arrives, not when somebody notices it | The company. **`privacidad@freezehub.io` exists** as an alias into `hola@` since `FZ-215` — see `docs/18-mail.md` |
| `«ÁREA RESPONSABLE»` | The person or area answerable for those requests, per Art. 13(d) | The company |
| `«FECHA DE ENTRADA EN VIGENCIA»` | The date the Política takes effect | The day it is published, after review |
| `«VIGENCIA DE LAS BASES DE DATOS»` | How long the databases will be kept, per Art. 13(f) | Follows the retention table in `docs/17-data-protection.md` §3, but must be stated as a period |
| `«URL DE LA POLÍTICA»` | The public address the full Política is served from. Art. 14 requires the Aviso to say where the Política can be read | Decided when it is published — likely `https://app.freezehub.io/legal/politica` |

## The RNBD question, which is not answered here

`OI-41` flagged it and it is still open. The **Registro Nacional de Bases de Datos** is a SIC
register, and whether a *responsable* must register turns on a threshold expressed in **UVT**
— a unit whose peso value the DIAN sets annually by resolution.

**This repository should not state either number.** The threshold has been amended more than
once, and a UVT figure written down here would be wrong within the year and would look
authoritative while being wrong. What is needed:

1. The company's **total assets** at the close of the previous fiscal year.
2. The **current UVT value**, from the DIAN resolution for the year in question.
3. The **current threshold and the current exemptions**, confirmed with counsel or from the
   SIC directly — not from this file.

Registration is a separate obligation from having a Política. Not being required to register
does not excuse not having one.

## What is deliberately not here

- **A risk register, a business continuity plan, or a vendor management policy.** Those
  describe a company, not a codebase (`17-data-protection.md` §8).
- **A breach register.** It is `OI-42`, and a register is a live record rather than a
  document — starting one in a git repository would be the wrong shape.
- **Any claim of a SOC 2 report.** None exists, and `gtm/`'s rule holds here more than
  anywhere: no claim that is not true today.
