package com.freezhub.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * `last_error` is bounded wherever it is written (`OI-40`).
 *
 * <p>Asserted on the entity rather than through a sender, because the entity is what makes
 * the bound unbypassable: every sender composes a short message today, and the column was
 * unbounded anyway because the dispatcher catches every {@code RuntimeException} and stores
 * {@code getMessage()} verbatim. A fifth writer added later goes through the same door.
 */
class NotificationLastErrorTest {

    private static final int MAX = 500;

    private Notification notification() {
        return new Notification(1L, 2L, 3L, NotificationEvent.ACTIVATED);
    }

    private static String longError() {
        return "x".repeat(4000);
    }

    @Test
    void truncatesAFailedAttempt() {
        Notification notification = notification();

        notification.markAttemptFailed(longError(), Instant.now());

        assertThat(notification.getLastError()).hasSize(MAX);
    }

    @Test
    void truncatesWhenAbandoned() {
        Notification notification = notification();

        notification.abandon(longError());

        assertThat(notification.getLastError()).hasSize(MAX);
    }

    @Test
    void truncatesWhenDeferred() {
        Notification notification = notification();

        notification.deferUntil(longError(), Instant.now().plusSeconds(60));

        assertThat(notification.getLastError()).hasSize(MAX);
    }

    @Test
    void keepsAShortMessageWhole() {
        // The bound must not cost the ordinary case anything: almost every real message is
        // one composed sentence, and truncating those would make the column less useful
        // than leaving it unbounded.
        Notification notification = notification();

        notification.markAttemptFailed("Webhook endpoint rejected or could not be reached", Instant.now());

        assertThat(notification.getLastError())
                .isEqualTo("Webhook endpoint rejected or could not be reached");
    }

    @Test
    void recordsSomethingWhenTheExceptionHadNoMessage() {
        // getMessage() is null for a NullPointerException and several others, and the
        // dispatcher passes it straight through. A FAILED row whose only account of itself
        // is null cannot be told from one that never recorded a reason at all.
        Notification notification = notification();

        notification.markAttemptFailed(null, Instant.now());

        assertThat(notification.getLastError()).isNotNull();
    }

    @Test
    void stillClearsOnEventualSuccess() {
        // A transient failure followed by success must not leave a stale error presented as
        // the current state (FZ-044). Bounding the write must not have broken that.
        Notification notification = notification();
        notification.markAttemptFailed(longError(), Instant.now());

        notification.markSent();

        assertThat(notification.getLastError()).isNull();
    }
}
