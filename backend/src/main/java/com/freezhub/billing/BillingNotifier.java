package com.freezhub.billing;

import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Tells an organization's administrators that a payment failed (FZ-084).
 *
 * <p>Plain SMTP, matching {@code EmailNotificationSender} — and deliberately not the
 * notification module, for the reason {@code FZ-083} found: a {@code Notification} needs a
 * restriction, and this is not about one.
 *
 * <p>Best effort, and failing to send never fails the webhook. Stripe redelivers anything
 * it does not get a 2xx for, so throwing here would make a mail outage replay a payment
 * event — and the subscription has already been moved to {@code PAST_DUE} by then, which
 * is the part that matters.
 */
@Component
@ConditionalOnProperty(name = "freezehub.notifications.email.from")
public class BillingNotifier {

    private static final Logger log = LoggerFactory.getLogger(BillingNotifier.class);

    private final JavaMailSender mailSender;
    private final UserRepository users;
    private final String fromAddress;

    public BillingNotifier(JavaMailSender mailSender, UserRepository users,
                           @Value("${freezehub.notifications.email.from}") String fromAddress) {
        this.mailSender = mailSender;
        this.users = users;
        this.fromAddress = fromAddress;
    }

    public void paymentFailed(Long organizationId) {
        List<String> administrators = users.findAllByOrganizationIdAndRoleAndDeactivatedAtIsNull(
                        organizationId, UserRole.ADMINISTRATOR).stream()
                .map(user -> user.getEmail())
                .toList();
        if (administrators.isEmpty()) {
            log.warn("Payment failed for organization {} and it has no administrator to tell",
                    organizationId);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(administrators.toArray(String[]::new));
        message.setSubject("FreezeHub: a payment did not go through");
        message.setText("""
                We could not take payment for your FreezeHub subscription.

                Nothing has changed yet: your deployment restrictions are still enforced and
                your team can still use FreezeHub. Update your payment details in Settings
                to avoid interruption.
                """);

        try {
            mailSender.send(message);
        } catch (MailException failed) {
            // Never rethrown: Stripe redelivers anything that is not a 2xx, so a mail
            // outage would replay the payment event rather than send the mail.
            log.warn("Could not tell organization {} that a payment failed: {}",
                    organizationId, failed.getClass().getSimpleName());
        }
    }
}
