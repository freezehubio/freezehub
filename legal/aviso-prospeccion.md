# Aviso de prospección

The block that goes in the footer of a **cold outreach** email, and nowhere else.

It is not the Aviso de Privacidad. That one (`aviso-de-privacidad.md`) is Decreto 1377
Art. 14's short form, shown at the moment somebody hands over their data. Here nobody hands
over anything, so the first message is the only moment the Art. 12 duty to inform can be
discharged, and it is discharged in the footer.

## There are two versions, and which one a recipient gets is not a style choice

`FZ-227`. A sources record for the current list showed that **15 of 19 addresses had never
been published anywhere** — Hunter derived them from the employer's naming convention. The
single footer written in `FZ-223` told every recipient their *"correo profesional figura en una
fuente de acceso público"*, which was false for those fifteen.

Art. 12 requires telling **this person** how **their** data was obtained. So the wording
follows the record:

| How the address was obtained | Version |
|---|---|
| Found published on a page | **A — published** |
| Derived from the employer's email pattern | **B — inferred** |

Version B does not claim Art. 10 for the address, because Art. 10(b) covers *datos de
naturaleza pública* and an address that was never published is not one. It claims Art. 10 only
for the name, role and employer — which **are** published, on the person's own professional
profile — and states plainly that the address was worked out. See `politica-de-tratamiento.md`
§ 2.1 and § 3.

---

## A — the address was published · Español

> Recibe este correo porque su nombre, cargo y correo profesional figuran en una fuente de
> acceso público y trabaja en un área a la que FreezeHub puede servir. Tratamos esos datos
> conforme al artículo 10 de la Ley 1581 de 2012 y a nuestra
> [Política de Tratamiento](https://app.freezehub.io/legal/privacy-policy); conservamos el
> registro de cómo se obtuvo cada dato y puede pedírnoslo.
>
> Responsable: Camilo Andres Hurtado Carvajal · Calle 50 # 99 - 74, Cali, Colombia ·
> privacidad@freezehub.io · +57 311 773 7625
>
> Si responde «no», no volvemos a escribirle. Si prefiere que eliminemos sus datos,
> escríbanos a privacidad@freezehub.io y lo hacemos de inmediato.

## A — the address was published · English

> You are receiving this because your name, role and work address appear in a publicly
> available source, and you work in an area FreezeHub can serve. We process that data under
> Article 10 of Colombia's Law 1581 of 2012 and our
> [Privacy Policy](https://app.freezehub.io/legal/privacy-policy?lang=en); we keep a record of
> how each item was obtained and you can ask us for it.
>
> Controller: Camilo Andres Hurtado Carvajal · Calle 50 # 99 - 74, Cali, Colombia ·
> privacidad@freezehub.io · +57 311 773 7625
>
> Reply "no" and we will not write again. If you would rather we deleted your data, email
> privacidad@freezehub.io and we will do it immediately.

---

## B — the address was inferred · Español

> Recibe este correo porque su nombre, cargo y empresa figuran en su perfil profesional
> público, y trabaja en un área a la que FreezeHub puede servir. **Su dirección de correo no
> estaba publicada: la dedujimos aplicando la nomenclatura que usa su empresa.** Tratamos su
> nombre, cargo y empresa conforme al artículo 10 de la Ley 1581 de 2012 y a nuestra
> [Política de Tratamiento](https://app.freezehub.io/legal/privacy-policy); conservamos el
> registro de cómo se obtuvo cada dato y puede pedírnoslo.
>
> Responsable: Camilo Andres Hurtado Carvajal · Calle 50 # 99 - 74, Cali, Colombia ·
> privacidad@freezehub.io · +57 311 773 7625
>
> Si responde «no», no volvemos a escribirle. Si prefiere que eliminemos sus datos,
> escríbanos a privacidad@freezehub.io y lo hacemos de inmediato.

## B — the address was inferred · English

> You are receiving this because your name, role and company appear on your public
> professional profile, and you work in an area FreezeHub can serve. **Your email address was
> not published: we worked it out from your company's address convention.** We process your
> name, role and company under Article 10 of Colombia's Law 1581 of 2012 and our
> [Privacy Policy](https://app.freezehub.io/legal/privacy-policy?lang=en); we keep a record of
> how each item was obtained and you can ask us for it.
>
> Controller: Camilo Andres Hurtado Carvajal · Calle 50 # 99 - 74, Cali, Colombia ·
> privacidad@freezehub.io · +57 311 773 7625
>
> Reply "no" and we will not write again. If you would rather we deleted your data, email
> privacidad@freezehub.io and we will do it immediately.

---

## Saying no has to be easier than ignoring it

`FZ-229`. The footer used to say *"responde «no»"*, which asks the recipient to compose a
message. Enough friction that the honest answer for most people is to delete the email
instead — and an opt-out nobody uses is not an opt-out, it is a sentence about one.

Every message now carries **two buttons**, one per right, because they are different rights
and have always been described as such here:

| Button | What it is | Subject it produces |
|---|---|---|
| **No me interesa** / **Not interested** | Art. 8(e), stop writing | `Remover - <their address>` |
| **Eliminar mis datos** / **Delete my data** | Art. 8(e), supresión | `Suprimir datos - <their address>` |

Both are `mailto:` links with the subject **and body pre-filled**, so a click opens the
recipient’s own mail app already written and they press send. Nothing is hosted, nothing is
tracked, and no link identifies who clicked — which a hosted unsubscribe URL would, since it
needs a per-recipient token to know who to remove.

The recipient’s address is in the subject so a request can be actioned without opening it.

**`&` must be written `&amp;` in those hrefs.** A bare ampersand in an HTML attribute starts
an entity reference, and the `body` parameter is silently dropped — the same class of defect
as `FZ-220`, in a different format.

### And a `List-Unsubscribe` header

RFC 2369, so Gmail and Outlook render their own unsubscribe control above the message, where
people look for one. Two ways out, neither requiring a word to be typed.

**No `List-Unsubscribe-Post`.** RFC 8058’s one-click flow requires an https endpoint that
accepts a POST and there is none; advertising it with only a `mailto:` target would promise a
capability that does not exist.

**The header is kept short deliberately.** Python’s default email policy treats
`List-Unsubscribe` as unstructured and RFC 2047-encodes it the moment the line needs folding
past 78 characters — and an encoded-word is not valid in that header, so clients ignore it.
Dropping the address from the subject keeps it under the limit; the request arrives *from*
the person, so their address was never needed there.

## Why each line is there, so none of them is cut as clutter

| Line | What requires it |
|---|---|
| Why you are receiving this | Art. 12(a) — the finalidad, and the only honest answer to "how did you get my address" |
| How the address was obtained | Art. 12 again. In version B it is the whole point: a recipient who knows their address was never published is owed the true explanation, not a vaguer one that happens to sound better |
| The Art. 10 citation | Scoped to what it actually covers. In B it names the name, role and employer and stops there |
| Controller identity and physical address | Decreto 1377 Art. 13(1). A recipient should not have to click to learn who is writing |
| Link to the Política | Art. 12(c) and Art. 14 — where the full terms can be read |
| `privacidad@freezehub.io` | Art. 13(1) — the channel where a consulta or reclamo arrives, which starts the statutory clock |
| Opt-out *and* deletion | Art. 8(e). "Reply no" stops the mail; it does not delete the record, and the two are different rights |

## What this block does not do

**Version B is an honest disclosure, not a lawful basis.** Describing an inference accurately
makes the *disclosure* truthful. It does not make the inferred address a *dato público*, and
Ley 1581 has no legitimate-interest limb to fall back on — see `17-data-protection.md` § 4.
For those recipients there is no authorization and no Art. 10 cover for the address itself, and
sending to them is a risk decision taken knowingly rather than a compliance position.

**SIC concepto 23-571469 does not close the gap either.** It places *"los datos corporativos
de una persona jurídica tales como correo institucional, celular corporativo, dirección de
contacto"* outside Ley 1581 — data belonging to the **legal entity**. A role address such as
`contacto@empresa.com` is squarely covered. An address that identifies a named natural person
is not what that wording describes, and a *concepto* is advisory guidance rather than binding
law in any case.

**It does not make the send lawful everywhere.** The rules on whether a commercial message may
be *sent at all* belong to the recipient's country and are separate from whether the data may
be processed here. Germany's UWG § 7 treats unsolicited B2B advertising email as unlawful
without prior consent. No footer cures that.

**It is not a substitute for counsel.** `OI-51` names Colombian counsel as a blocker on all of
these documents, and the status of a derived corporate address is the first question to put.
