package com.freezhub.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Emails a demo request to whoever answers them (`FZ-214`).
 *
 * <p><strong>Why, when Slack already exists.</strong> Slack tells whoever is looking at
 * Slack. A lead that arrives while nobody is is the one that is still unanswered on Monday,
 * and the first real submission on the live site was announced to nothing at all, because
 * neither channel had ever been configured in the deployed environment.
 *
 * <p>{@code @ConditionalOnProperty} on the from-address, matching {@code BillingNotifier}:
 * the sender is only worth constructing where mail is configured at all. The recipient is a
 * separate property because they are separate facts — one is the address the product sends
 * <em>from</em>, the other is an internal mailbox.
 *
 * <p><strong>Reply-To is the prospect.</strong> The whole purpose is that somebody replies,
 * and making that the default costs one line here and saves a copy-paste every time.
 */
@Component
@ConditionalOnProperty(name = "freezehub.notifications.email.from")
public class EmailDemoRequestChannel implements DemoRequestChannel {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String toAddress;

    public EmailDemoRequestChannel(JavaMailSender mailSender,
                                   @Value("${freezehub.notifications.email.from}") String fromAddress,
                                   @Value("${freezehub.demo-requests.email-to:}") String toAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.toAddress = toAddress;
    }

    @Override
    public boolean isConfigured() {
        return toAddress != null && !toAddress.isBlank();
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public void announce(DemoRequest request) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toAddress);
        message.setReplyTo(request.getEmail());
        message.setSubject("Demo requested: " + request.getCompany());
        message.setText(bodyFor(request));

        try {
            mailSender.send(message);
        } catch (MailException failed) {
            // Thrown, not swallowed: this codebase owns the retry for demo requests, so a
            // failure the loop cannot see is a failure that never gets retried. The class
            // name only — a mail exception's message can carry the server and the
            // credentials it rejected, and this string is written to `notify_error`.
            throw new DemoRequestChannelException(
                    "could not be sent: " + failed.getClass().getSimpleName());
        }
    }

    /**
     * Plain text, and every field on its own line.
     *
     * <p>No escaping problem to have — unlike the Slack payload, which is JSON — but the
     * same rule applies for a different reason: this is read by a person deciding whether to
     * reply, so the shape matters more than the prose.
     */
    private String bodyFor(DemoRequest request) {
        StringBuilder body = new StringBuilder()
                .append("Company:  ").append(request.getCompany()).append("\n")
                .append("Name:     ").append(request.getName()).append("\n")
                .append("Email:    ").append(request.getEmail()).append("\n");
        if (request.getTeamSize() != null && !request.getTeamSize().isBlank()) {
            body.append("Engineers: ").append(request.getTeamSize()).append("\n");
        }
        if (request.getSource() != null && !request.getSource().isBlank()) {
            body.append("From:     ").append(request.getSource()).append("\n");
        }
        if (request.getMessage() != null && !request.getMessage().isBlank()) {
            body.append("\n").append(request.getMessage()).append("\n");
        }
        body.append("\nReply to this email to answer them directly.\n");
        return body.toString();
    }
}
