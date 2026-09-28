package com.freezhub.demo;

/**
 * Somewhere a demo request can be announced (`FZ-214`).
 *
 * <p>Extracted from {@link DemoRequestNotifier}, which until now was the retry loop and the
 * Slack call in one class. A second destination could not be added without separating them,
 * and there is a second destination because Slack tells whoever is looking at Slack — an
 * emailed lead is the one that is still there on Monday.
 *
 * <p><strong>Implementations throw on failure</strong>, unlike {@code BillingNotifier},
 * which swallows a {@code MailException} on purpose. The difference is who retries: Stripe
 * redelivers a webhook it gets no 2xx for, so throwing there would replay a payment event.
 * Here <em>this</em> codebase owns the retry, through {@code RetryPolicy} and the columns on
 * {@code demo_request}, so a failure has to be visible to the loop or it cannot be retried
 * at all.
 */
public interface DemoRequestChannel {

    /**
     * Whether this channel has what it needs to send.
     *
     * <p>Absent configuration is a normal state, not an error: a deployment may have Slack
     * and no mail server, or the reverse, or neither — and with neither the request is still
     * recorded, because the lead is the row and not the message.
     */
    boolean isConfigured();

    /** A short name for the log and for {@code notify_error}. Never a credential. */
    String name();

    /**
     * @throws DemoRequestChannelException when it could not be delivered
     */
    void announce(DemoRequest request);
}
