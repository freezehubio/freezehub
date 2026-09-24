# Aviso de Privacidad · Privacy Notice

The short notice shown **at the moment data is collected** — on the signup form and the demo
request form — where displaying the whole Política is not practical. Required by Decreto 1377
Art. 14, whose four required elements are the four sections below.

It is published in both languages deliberately: `POST /api/signup` is self-serve, and a
product anyone can sign up for does not get to choose where its data subjects live.

---

## Español

**Responsable:** «RAZÓN SOCIAL», NIT «NIT» · «DIRECCIÓN FÍSICA», «DOMICILIO», Colombia
«CORREO DE PRIVACIDAD» · «TELÉFONO»

**Tratamiento y finalidad.** Sus datos de identificación y contacto se tratan para atender su
solicitud, crear y administrar su cuenta, prestarle el servicio, facturarlo y cumplir las
obligaciones legales aplicables. No se utilizan para ninguna otra finalidad, no se venden y no
se comparten con terceros distintos de los Encargados publicados en nuestra lista de
subencargados.

**Sus derechos.** Usted puede conocer, actualizar y rectificar sus datos; solicitar prueba de
la autorización otorgada; ser informado sobre el uso dado a sus datos; revocar la autorización
y solicitar la supresión cuando no exista un deber legal o contractual que lo impida; acceder
gratuitamente a ellos; y presentar quejas ante la Superintendencia de Industria y Comercio.

**Cómo consultar la Política.** La Política de Tratamiento de Datos Personales completa, y
cualquier cambio sustancial que se le introduzca, está disponible de forma permanente en
«URL DE LA POLÍTICA». Los cambios sustanciales se comunicarán por el canal de contacto que
usted haya proporcionado, antes de su entrada en vigencia.

Al enviar este formulario usted declara haber leído este aviso y autoriza de manera previa,
expresa e informada el tratamiento de sus datos en los términos descritos.

---

## English

**Data controller:** «RAZÓN SOCIAL», tax ID «NIT» · «DIRECCIÓN FÍSICA», «DOMICILIO», Colombia
«CORREO DE PRIVACIDAD» · «TELÉFONO»

**What we do with your data.** Your identification and contact details are used to answer your
request, create and administer your account, provide the service, invoice you, and meet our
legal obligations. They are used for nothing else, are not sold, and are not shared with
anyone other than the processors listed in our published subprocessor list.

**Your rights.** You may access, update and correct your data; ask for proof of the
authorization you gave; be told how your data has been used; withdraw that authorization and
ask for deletion where no legal or contractual duty prevents it; access your data free of
charge; and complain to the Colombian Superintendency of Industry and Commerce.

If you are in the EEA or the UK, the equivalent rights under the GDPR are honoured on the same
channel and within the same deadlines, whichever are shorter.

**Where to read the full policy.** The full Data Processing Policy, and any substantial change
to it, is permanently available at «URL DE LA POLÍTICA». Substantial changes are notified to
you on the contact channel you provided, before they take effect.

By submitting this form you confirm you have read this notice and give prior, express and
informed authorization for your data to be processed as described.

---

## Note for whoever wires this into the product

**The text shown must be stored with the authorization, not just displayed.** Ley 1581
Art. 8(b) gives the Titular the right to request *proof* of the authorization given, and proof
means the timestamp and the exact wording that was on screen at that moment — not a boolean.

`OI-37` records that this is **not currently possible**: `demo_request` has no column for
either, and the form has no such control. A checkbox alone would not close it. So this notice
can be published as a page today, and the authorization it describes cannot yet be proven,
which is the gap `OI-37` exists to track.
