package com.freezhub.demo;

import com.freezhub.notification.RetryPolicy;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
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
    private final DemoAcknowledgementMessage message;
    private final String fromAddress;
    private final String bookingUrl;
    private final String policyUrl;

    public DemoRequestAcknowledger(DemoRequestRepository requests, JavaMailSender mailSender,
                                   DemoAcknowledgementMessage message,
                                   @Value("${freezehub.demo-requests.acknowledge-from}") String fromAddress,
                                   @Value("${freezehub.demo-requests.booking-url:}") String bookingUrl,
                                   @Value("${freezehub.demo-requests.policy-url:}") String policyUrl) {
        this.requests = requests;
        this.mailSender = mailSender;
        this.message = message;
        this.fromAddress = fromAddress;
        this.bookingUrl = bookingUrl;
        this.policyUrl = policyUrl;
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

    /**
     * Multipart: HTML, with the plain text as its alternative.
     *
     * <p>Both parts, not one. A multipart message with no text part is a spam signal to some
     * filters, and some readers show only that part — so the two have to say the same things
     * or they become different promises to the same person.
     */
    private void send(DemoRequest request) {
        MimeMessage mime = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper =
                    new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(request.getEmail());
            // Replying reaches the founder's own mailbox, which is the point of sending it
            // from there rather than from a company alias.
            helper.setReplyTo(fromAddress);
            helper.setSubject(message.subject(request));
            helper.setText(message.text(request, bookingUrl),
                    message.html(request, bookingUrl, policyUrl));
        } catch (MessagingException malformed) {
            // Not a delivery failure: the message could not be built. Wrapped so the caller
            // records and retries it the same way, because the outcome for the prospect is
            // identical — nobody wrote to them.
            throw new MailPreparationException(malformed);
        }

        mailSender.send(mime);
    }

}
