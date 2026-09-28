package com.freezhub.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the acknowledgement actually says (`FZ-217`). Pure, so the awkward cases are cheap. */
class DemoAcknowledgementMessageTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final String BOOKING = "https://calendar.app.google/freezehub-demo";
    private static final String POLICY = "https://app.freezehub.io/legal/politica";

    private DemoAcknowledgementMessage message;
    private DemoRequest request;

    @BeforeEach
    void setUp() {
        message = new DemoAcknowledgementMessage();
        request = new DemoRequest("Dana Okafor", "dana@northwind.test", "Northwind Trading",
                "35", "Las ventanas viven en tres hilos de Slack.", "landing", NOW);
    }

    @Test
    @DisplayName("greets by first name and names their company")
    void personalised() {
        String html = message.html(request, BOOKING, POLICY);

        assertThat(html).contains("Hola Dana,");
        assertThat(html).contains("Northwind Trading");
        assertThat(html).doesNotContain("Dana Okafor,");
    }

    @Test
    @DisplayName("signs as the founder, with a phone number")
    void signature() {
        // Sent from a person rather than a company alias, on purpose: at this stage the
        // reply is the product of the email.
        String html = message.html(request, BOOKING, POLICY);

        assertThat(html).contains("Camilo Hurtado").contains("Fundador")
                .contains("+57 311 773 7625");
    }

    @Test
    @DisplayName("carries the booking button when a calendar is configured")
    void bookingPresent() {
        String html = message.html(request, BOOKING, POLICY);

        assertThat(html).contains(BOOKING).contains("Agendar mi demo");
    }

    @Test
    @DisplayName("removes the button AND its sentence when no calendar is configured")
    void bookingAbsent() {
        // A "choose a time" button with nothing behind it is an offer to somebody who has
        // already decided to meet — and the sentence introducing it is just as wrong.
        String html = message.html(request, "", POLICY);

        assertThat(html).doesNotContain("Agendar mi demo");
        assertThat(html).doesNotContain("elige directamente el horario");
        assertThat(html).doesNotContain("__BOOKING_URL__");
        // The rest of the message survives.
        assertThat(html).contains("Hola Dana,").contains("Responde a este correo");
    }

    @Test
    @DisplayName("claims a privacy policy only when there is one to link to")
    void policyAbsent() {
        // OI-51: the Politica is drafted and unpublished. Linking to a 404 while asserting
        // the data is handled under it is worse than making no claim.
        String html = message.html(request, BOOKING, "");

        assertThat(html).doesNotContain("Política de Tratamiento");
        assertThat(html).doesNotContain("__POLICY_URL__");
        // The legal identification stays — it is required regardless.
        assertThat(html).contains("Calle 50 # 99 - 74, Cali");
    }

    @Test
    @DisplayName("escapes what came from the form")
    void escaping() {
        // `O'Brien & Sons <Ltd>` is not hypothetical, and unescaped it breaks the markup
        // around it -- the mistake FZ-060 made in the audit trail.
        DemoRequest awkward = new DemoRequest("Ana <script>", "a@b.test", "O'Brien & Sons <Ltd>",
                null, null, "landing", NOW);

        String html = message.html(awkward, BOOKING, POLICY);

        assertThat(html).contains("O&#39;Brien &amp; Sons &lt;Ltd&gt;".replace("&#39;", "'"));
        assertThat(html).doesNotContain("<script>");
    }

    @Test
    @DisplayName("the text alternative says the same things as the HTML")
    void textAlternative() {
        // Some readers see only this part, so the two must not become different promises.
        String text = message.text(request, BOOKING);

        assertThat(text).contains("Hola Dana,").contains("Northwind Trading")
                .contains(BOOKING).contains("Camilo Hurtado").contains("+57 311 773 7625");
    }

    @Test
    @DisplayName("the text alternative drops the link too when there is no calendar")
    void textWithoutBooking() {
        String text = message.text(request, "");

        assertThat(text).doesNotContain("horario que mejor te funcione");
        assertThat(text).contains("Responde a este correo");
    }

    @Test
    @DisplayName("promises no delivery time the product cannot keep")
    void promisesNothingUntrue() {
        String html = message.html(request, BOOKING, POLICY);

        assertThat(html).doesNotContainIgnoringCase("24 horas");
        assertThat(html).doesNotContainIgnoringCase("día hábil");
        assertThat(message.text(request, BOOKING)).doesNotContainIgnoringCase("24 horas");
    }

    @Test
    @DisplayName("ships no developer notes to the prospect")
    void commentsStripped() {
        // The template carries twenty lines about the colour ramp and which token comes
        // from where. None of that is the recipient's business.
        String html = message.html(request, BOOKING, POLICY);

        assertThat(html).doesNotContain("<!--");
        assertThat(html).doesNotContain("Sistema visual");
        assertThat(html).doesNotContain("DemoRequestAcknowledger");
        // The hidden preview line is a div, not a comment, and must survive.
        assertThat(html).contains("Elige el horario que mejor te funcione y conectemos pronto.");
    }

    @Test
    @DisplayName("the template has no Outlook conditional comments, which stripping would break")
    void noConditionalComments() throws Exception {
        // Comment stripping is unconditional. If an <!--[if mso]> block is ever added to the
        // template it is markup rather than commentary, and this catches it before a broken
        // layout reaches anybody's inbox.
        String raw = new String(getClass().getClassLoader()
                .getResourceAsStream("email/demo-acknowledgement.html").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(raw).doesNotContain("<!--[if");
    }

    @Test
    @DisplayName("no placeholder survives into a sent message")
    void noPlaceholdersLeft() {
        for (String html : new String[] {
                message.html(request, BOOKING, POLICY),
                message.html(request, "", ""),
        }) {
            assertThat(html).doesNotContain("__").doesNotContain("{{");
        }
    }
}
