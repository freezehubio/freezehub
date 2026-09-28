package com.freezhub.demo;

import com.freezhub.notification.RetryPolicy;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells the person who asked for a demo that we have it (`FZ-217`).
 *
 * <p><strong>A different promise from {@link DemoRequestNotifier}</strong>, which is why it
 * is a different class with its own columns rather than a third {@code DemoRequestChannel}.
 * A channel announces the lead <em>to us</em>; if one fails while another works, the lead is
 * still handled. This is a message <em>to them</em>: nobody else can substitute for it, and
 * its failure must not mark the internal announcement done, nor the reverse.
 *
 * <p><strong>From an address a human reads.</strong> Not
 * {@code freezehub.notifications.email.from} — that is
 * {@code notificaciones@}, send-only by design ({@code 18-mail.md} §3), because an
 * out-of-office answering a freeze announcement should die at the boundary. An
 * acknowledgement is the opposite: it invites a reply, and a prospect who answers it should
 * reach somebody. Hence its own property.
 *
 * <p><strong>Every one of these fails while SES is in the sandbox.</strong> The recipient is
 * a prospect, whose address is not a verified identity, so SES rejects it. That is recorded
 * per row and retried on the usual backoff rather than swallowed — the state to avoid is a
 * queue of people who were never answered and nothing saying so.
 */
@Service
@ConditionalOnProperty(name = "freezehub.demo-requests.acknowledge-from")
public class DemoRequestAcknowledger {

    private static final Logger log = LoggerFactory.getLogger(DemoRequestAcknowledger.class);
    private static final int BATCH_SIZE = 50;

    private final DemoRequestRepository requests;
    private final JavaMailSender mailSender;
    private final String fromAddress;

    public DemoRequestAcknowledger(DemoRequestRepository requests, JavaMailSender mailSender,
                                   @Value("${freezehub.demo-requests.acknowledge-from}") String fromAddress) {
        this.requests = requests;
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    /**
     * Answers whoever is waiting.
     *
     * @return how many were acknowledged on this pass
     */
    @Transactional
    public int acknowledgePending(Instant now) {
        List<DemoRequest> pending = requests.findPendingAcknowledgement(
                now, RetryPolicy.MAX_ATTEMPTS, Limit.of(BATCH_SIZE));

        int sent = 0;
        for (DemoRequest request : pending) {
            try {
                send(request);
                request.markAcknowledged(now);
                sent += 1;
            } catch (MailException failed) {
                // The class name only. A mail exception's message can carry the server and
                // the credentials it rejected, and this string is written to a column.
                String error = "could not be sent: " + failed.getClass().getSimpleName();
                request.markAcknowledgementFailed(error,
                        RetryPolicy.nextAttemptAfter(request.getAcknowledgeAttempts(), now));
                log.warn("Demo request {} could not be acknowledged (attempt {}): {}",
                        request.getId(), request.getAcknowledgeAttempts() + 1, error);
            }
        }
        return sent;
    }

    private void send(DemoRequest request) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(request.getEmail());
        // Replying to this reaches the same mailbox it came from, which is the point.
        message.setReplyTo(fromAddress);
        message.setSubject("Thanks for asking about FreezeHub");
        message.setText(bodyFor(request));

        mailSender.send(message);
    }

    /**
     * Plain text, short, and it promises only what is true.
     *
     * <p>No delivery estimate the product cannot keep — a human answers these, and
     * "within one business day" written here becomes a commitment nobody agreed to. It says
     * a person will reply, and that replying to this message reaches one.
     *
     * <p>Their own words are quoted back. It costs a line and it is the difference between
     * a receipt and a form letter: they can see the request arrived intact.
     */
    private String bodyFor(DemoRequest request) {
        StringBuilder body = new StringBuilder()
                .append("Hi ").append(firstNameOf(request.getName())).append(",\n\n")
                .append("Thanks for asking about FreezeHub — we have your request and a person ")
                .append("will get back to you.\n\n")
                .append("FreezeHub is one authoritative place to declare a deployment freeze, ")
                .append("tell everyone, and let your pipelines ask before they deploy. If that ")
                .append("is not what you were expecting, say so in a reply and we will not ")
                .append("waste your time.\n\n");

        if (request.getMessage() != null && !request.getMessage().isBlank()) {
            body.append("You told us:\n\n  ").append(request.getMessage()).append("\n\n");
        }

        body.append("Just reply to this email if you want to add anything.\n\n")
                .append("— The FreezeHub team\n");
        return body.toString();
    }

    /**
     * The first word of whatever they typed.
     *
     * <p>Greeting somebody by the whole of "Dana Okafor" reads like a database, and the
     * database is exactly what it is. A single word, or the whole string when there is only
     * one — never empty, because the field is required.
     */
    private String firstNameOf(String name) {
        String trimmed = name.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }
}
