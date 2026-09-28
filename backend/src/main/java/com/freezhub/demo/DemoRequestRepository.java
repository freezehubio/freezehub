package com.freezhub.demo;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Demo requests (FZ-083).
 *
 * <p>No method here takes an organization id, and none should: this table sits outside the
 * tenant boundary because a demo request belongs to nobody yet. If a query with an
 * organization filter ever appears here, something has gone wrong upstream.
 */
public interface DemoRequestRepository extends JpaRepository<DemoRequest, Long> {

    /**
     * Requests still waiting to be announced, oldest first.
     *
     * <p>Bounded by attempts as well as by time, so a permanently broken webhook stops
     * being retried instead of being hit on every pass for ever ({@code RetryPolicy}).
     */
    @Query("""
            select d from DemoRequest d
            where d.notifiedAt is null
              and d.nextNotifyAt <= :now
              and d.notifyAttempts < :maxAttempts
            order by d.id asc
            """)
    List<DemoRequest> findPendingNotification(@Param("now") Instant now,
                                              @Param("maxAttempts") int maxAttempts,
                                              Limit limit);

    /**
     * Requests whose sender has not yet been told we have it, oldest first (`FZ-217`).
     *
     * <p>Deliberately <b>not</b> filtered on {@code notifiedAt}. Acknowledging the prospect
     * and announcing the lead internally are separate promises to separate people: a Slack
     * outage must not leave somebody waiting for a reply that was never the same message.
     */
    @Query("""
            select d from DemoRequest d
            where d.acknowledgedAt is null
              and d.nextAcknowledgeAt <= :now
              and d.acknowledgeAttempts < :maxAttempts
            order by d.id asc
            """)
    List<DemoRequest> findPendingAcknowledgement(@Param("now") Instant now,
                                                 @Param("maxAttempts") int maxAttempts,
                                                 Limit limit);
}
