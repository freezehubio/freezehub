package com.freezhub.demo;

import com.freezhub.notification.RetryPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells somebody a demo was requested (FZ-083, given a second channel by FZ-214).
 *
 * <p><strong>Why this is not the notification module.</strong> The backlog assumed it could
 * be. It cannot: a {@code Notification} requires a non-null {@code organizationId} <em>and</em>
 * {@code restrictionId}, and a demo request has neither. What <em>is</em> reused is the part
 * worth reusing — {@link RetryPolicy}, a pure function of attempt count with no coupling at
 * all. Same backoff, same give-up point, no duplicated schedule to drift.
 *
 * <p>This class is now only the loop. Where a request goes is a {@link DemoRequestChannel},
 * of which there are two, and it asks every one that is configured.
 *
 * <p><strong>One request, one set of columns, two channels.</strong> {@code demo_request}
 * has a single {@code notified_at}, so "notified" has to mean something definite:
 *
 * <ul>
 *   <li><em>every</em> configured channel succeeded — notified, no error recorded;
 *   <li><em>some</em> succeeded — <strong>still notified</strong>, with the failures written
 *       to {@code notify_error} so they are findable by query rather than only in a log;
 *   <li><em>none</em> succeeded — not notified, retried on the usual backoff.
 * </ul>
 *
 * <p>Partial success counts as notified deliberately. The alternative is retrying every
 * channel until all of them work, which sends the channel that already succeeded the same
 * lead five more times. A duplicate in Slack is noise; the purpose — somebody learned about
 * this lead — was met on the first pass. The cost is that a permanently broken second
 * channel keeps working as a warning rather than a failure, which is why it lands in a
 * column and not only in the log.
 *
 * <p>With no channel configured nothing is sent and nothing is retried — the request is
 * still recorded, because <strong>the lead is the row, not the message</strong>.
 */
@Service
public class DemoRequestNotifier {

    private static final Logger log = LoggerFactory.getLogger(DemoRequestNotifier.class);
    private static final int BATCH_SIZE = 50;

    private final DemoRequestRepository requests;
    private final List<DemoRequestChannel> channels;

    public DemoRequestNotifier(DemoRequestRepository requests, List<DemoRequestChannel> channels) {
        this.requests = requests;
        this.channels = channels;
    }

    /** Whether anything at all can be told. */
    public boolean isConfigured() {
        return channels.stream().anyMatch(DemoRequestChannel::isConfigured);
    }

    /**
     * Sends whatever is due.
     *
     * @return how many were announced on this pass, counting a partial success as announced
     */
    @Transactional
    public int notifyPending(Instant now) {
        List<DemoRequestChannel> configured = channels.stream()
                .filter(DemoRequestChannel::isConfigured)
                .toList();
        if (configured.isEmpty()) {
            return 0;
        }

        List<DemoRequest> pending = requests.findPendingNotification(
                now, RetryPolicy.MAX_ATTEMPTS, Limit.of(BATCH_SIZE));

        int sent = 0;
        for (DemoRequest request : pending) {
            List<String> failures = new ArrayList<>();
            int delivered = 0;

            for (DemoRequestChannel channel : configured) {
                try {
                    channel.announce(request);
                    delivered += 1;
                } catch (DemoRequestChannelException failed) {
                    failures.add(channel.name() + ": " + failed.getMessage());
                }
            }

            if (delivered > 0) {
                if (failures.isEmpty()) {
                    request.markNotified(now);
                } else {
                    request.markNotifiedWithFailures(now, String.join("; ", failures));
                    log.warn("Demo request {} announced on {} of {} channels: {}",
                            request.getId(), delivered, configured.size(),
                            String.join("; ", failures));
                }
                sent += 1;
            } else {
                String error = String.join("; ", failures);
                request.markNotificationFailed(error,
                        RetryPolicy.nextAttemptAfter(request.getNotifyAttempts(), now));
                log.warn("Demo request {} could not be announced anywhere (attempt {}): {}",
                        request.getId(), request.getNotifyAttempts() + 1, error);
            }
        }
        return sent;
    }
}
