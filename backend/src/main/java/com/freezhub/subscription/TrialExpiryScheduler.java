package com.freezhub.subscription;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.freezhub.shared.scheduling.SchedulerLock;
import java.time.Duration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Suspends trials that have run out (FZ-081).
 *
 * <p>Deliberately thin, following {@code RestrictionLifecycleScheduler}: the logic is in
 * {@link SubscriptionService} where it can be tested against an explicit clock rather than
 * by waiting.
 *
 * <p>Two triggers for the same reason as the lifecycle reconciler — an hourly tick alone
 * leaves a restart-shaped gap, and a trial that ended while the process was down should be
 * closed on the way back up rather than up to an hour later.
 *
 * <p>Hourly rather than by the minute: a trial ending is not time-critical to the hour,
 * and a customer whose trial ends at 09:00 losing write access at 09:47 is better than a
 * sweep running 1,440 times a day to be exact about something nobody is watching.
 *
 * <p>Disabled with {@code freezehub.subscriptions.enabled=false}, which the tests use so
 * the suite is not racing a background job that suspends organizations underneath it.
 */
@Component
@ConditionalOnProperty(name = "freezehub.subscriptions.enabled", havingValue = "true", matchIfMissing = true)
public class TrialExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrialExpiryScheduler.class);

    private static final Duration LEASE = Duration.ofMinutes(10);

    private final SubscriptionService subscriptions;
    private final SchedulerLock lock;

    public TrialExpiryScheduler(SubscriptionService subscriptions, SchedulerLock lock) {
        this.subscriptions = subscriptions;
        this.lock = lock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        // Two instances booting together is precisely when this collides (FZ-121).
        lock.runIfAcquired("trial-expiry", LEASE, this::sweep);
    }

    @Scheduled(fixedDelayString = "${freezehub.subscriptions.interval:PT1H}")
    public void sweepPeriodically() {
        lock.runIfAcquired("trial-expiry", LEASE, this::sweep);
    }

    private void sweep() {
        int expired = subscriptions.expireTrials(Instant.now());
        if (expired > 0) {
            log.info("Moved {} organization(s) to the FREE plan; trial ended", expired);
        }
    }
}
