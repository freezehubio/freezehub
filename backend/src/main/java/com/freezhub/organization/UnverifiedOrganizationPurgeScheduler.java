package com.freezhub.organization;

import com.freezhub.shared.scheduling.SchedulerLock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link UnverifiedOrganizationPurge} (FZ-082).
 *
 * <p>Thin, following {@code TrialExpiryScheduler} and {@code RestrictionLifecycleScheduler}:
 * the logic takes an explicit clock so it can be tested without waiting.
 *
 * <p>Hourly. The threshold is seven days, so the difference between deleting at day seven
 * and at day seven plus an hour is nothing anybody can observe.
 *
 * <p>Locked, because five scheduled jobs running on every instance is a defect this
 * codebase already had once ({@code FZ-121}): at two instances the purge would race itself
 * and try to delete the same organization twice.
 *
 * <p>Disabled with {@code freezehub.signup.purge.enabled=false}, which the tests use so the
 * suite is not racing a job that deletes organizations underneath it.
 */
@Component
@ConditionalOnProperty(name = "freezehub.signup.purge.enabled", havingValue = "true", matchIfMissing = true)
public class UnverifiedOrganizationPurgeScheduler {

    private static final Duration LEASE = Duration.ofMinutes(10);

    private final UnverifiedOrganizationPurge purge;
    private final SchedulerLock lock;

    public UnverifiedOrganizationPurgeScheduler(UnverifiedOrganizationPurge purge, SchedulerLock lock) {
        this.purge = purge;
        this.lock = lock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        lock.runIfAcquired("unverified-organization-purge", LEASE, this::sweep);
    }

    @Scheduled(fixedDelayString = "${freezehub.signup.purge.interval:PT1H}")
    public void sweepPeriodically() {
        lock.runIfAcquired("unverified-organization-purge", LEASE, this::sweep);
    }

    private void sweep() {
        purge.purge(Instant.now());
    }
}
