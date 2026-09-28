package com.freezhub.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Renders the acknowledgement email (`FZ-217`).
 *
 * <p>The HTML is a file — {@code resources/email/demo-acknowledgement.html} — and not a
 * string in Java, because it is a design artefact the operator wrote and will edit. The
 * company's own details live in it for the same reason: a phone number and a postal address
 * change rarely, and six configuration properties nobody sets differently per environment
 * would be worse than one file in the repository.
 *
 * <p><strong>Substitution, not a template engine.</strong> Three tokens and two optional
 * blocks do not justify a dependency, and a template engine on HTML assembled from user
 * input is a larger surface than this needs.
 *
 * <p><strong>Two blocks can disappear, and both must.</strong> A "Agendar mi demo" button
 * with no calendar behind it is an offer to somebody who has already decided to meet, and a
 * sentence claiming a privacy policy that links to a 404 is worse than making no claim —
 * the Política is drafted and unpublished (`OI-51`).
 */
@Component
public class DemoAcknowledgementMessage {

    private static final String TEMPLATE = "email/demo-acknowledgement.html";

    private static final Pattern BOOKING_BLOCK =
            Pattern.compile("<!--BOOKING_START-->.*?<!--BOOKING_END-->", Pattern.DOTALL);
    private static final Pattern POLICY_BLOCK =
            Pattern.compile("<!--POLICY_START-->.*?<!--POLICY_END-->", Pattern.DOTALL);

    /**
     * Every remaining comment, stripped after the blocks above have been used.
     *
     * <p>The template carries twenty lines of notes for whoever edits it — the colour
     * ramp, which token comes from where. There is no reason for a prospect to receive
     * them, and every reason not to ship internal notes to somebody outside the company.
     *
     * <p>Safe here because this template contains no Outlook conditional comments
     * ({@code <!--[if mso]>}), which are markup rather than commentary. A test asserts that
     * stays true; if one is ever added, this has to become selective.
     */
    private static final Pattern COMMENT = Pattern.compile("<!--(?!\\[if).*?-->", Pattern.DOTALL);

    private final String template;

    public DemoAcknowledgementMessage() {
        try {
            this.template = new ClassPathResource(TEMPLATE)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException missing) {
            // At construction, so a packaging mistake fails the context rather than being
            // discovered by the first prospect who does not receive an email.
            throw new UncheckedIOException("Cannot read " + TEMPLATE, missing);
        }
    }

    public String html(DemoRequest request, String bookingUrl, String policyUrl) {
        String out = template;

        out = hasText(bookingUrl)
                ? out.replace("__BOOKING_URL__", escape(bookingUrl))
                : BOOKING_BLOCK.matcher(out).replaceAll(Matcher.quoteReplacement(""));

        out = hasText(policyUrl)
                ? out.replace("__POLICY_URL__", escape(policyUrl))
                : POLICY_BLOCK.matcher(out).replaceAll(Matcher.quoteReplacement(""));

        out = COMMENT.matcher(out).replaceAll(Matcher.quoteReplacement(""));

        return out.replace("__FIRST_NAME__", escape(firstNameOf(request.getName())))
                .replace("__COMPANY__", escape(request.getCompany()));
    }

    /**
     * The plain-text alternative, sent alongside the HTML rather than instead of it.
     *
     * <p>Not decoration: a multipart message with no text part is treated as a spam signal
     * by some filters, and some readers see only this. It says the same things, so the two
     * cannot drift into different promises.
     */
    public String text(DemoRequest request, String bookingUrl) {
        StringBuilder body = new StringBuilder()
                .append("Hola ").append(firstNameOf(request.getName())).append(",\n\n")
                .append("Gracias por tu interés en FreezeHub. Recibimos tu solicitud de demo y ")
                .append("nos encantaría conectar contigo lo más pronto posible.\n\n");

        if (hasText(bookingUrl)) {
            body.append("Elige directamente el horario que mejor te funcione:\n")
                    .append(bookingUrl).append("\n\n");
        }

        body.append("Serán unos 30 minutos para ver cómo ").append(request.getCompany())
                .append(" maneja hoy las ventanas en las que no se debe desplegar, mostrarte ")
                .append("cómo FreezeHub puede ayudar y resolver tus preguntas.\n\n")
                .append("¿Prefieres que te contactemos nosotros? Responde a este correo.\n\n")
                .append("¡Hablamos pronto!\n\n")
                .append("Camilo Hurtado\n")
                .append("Fundador, FreezeHub\n")
                .append("+57 311 773 7625\n");
        return body.toString();
    }

    /** The subject, kept beside the body so the two are edited together. */
    public String subject(DemoRequest request) {
        return "Recibimos tu solicitud de demo — FreezeHub";
    }

    /**
     * Minimal HTML escaping for the two values that come from the form.
     *
     * <p>A company called {@code O'Brien & Sons <Ltd>} is not hypothetical, and without this
     * it would break the markup around it — the same class of mistake `FZ-060` made in the
     * audit trail and `FZ-083` avoided in the Slack payload by building JSON with Jackson.
     */
    private String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * The first word of whatever they typed.
     *
     * <p>Greeting somebody by the whole of "Dana Okafor" reads like a database, which is
     * exactly what it is.
     */
    private String firstNameOf(String name) {
        String trimmed = name.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
