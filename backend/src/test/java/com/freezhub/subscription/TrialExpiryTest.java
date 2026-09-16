package com.freezhub.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.freezhub.ContainersConfig;
import com.freezhub.audit.AuditAction;
import com.freezhub.audit.AuditRepository;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * A trial expires into the free plan, not into suspension (FZ-144, D-33).
 *
 * <p>The criterion this class exists for is {@link #aPaidSubscriptionIsNeverSweptToFree}.
 * A paying customer's hard freezes are in force, and a plan that can only advise would stop
 * enforcing them — the failure D-21 calls worse than an outage, arriving as a side effect of
 * a billing event.
 */
@SpringBootTest
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class TrialExpiryTest {

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private AuditRepository auditRepository;

    private Long organizationId;

    @BeforeEach
    void setUp() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Northwind " + System.nanoTime()));
        organizationId = organization.getId();
    }

    @Test
    void anExpiredTrialMovesToTheFreePlanAndStaysActive() {
        Instant now = Instant.now();
        subscriptionRepository.saveAndFlush(
                Subscription.startTrial(organizationId, now.minus(Duration.ofDays(20))));

        subscriptionService.expireTrials(now);

        assertThat(subscriptionRepository.findByOrganizationId(organizationId)).get()
                .satisfies(subscription -> {
                    assertThat(subscription.getPlan()).isEqualTo(Plan.FREE);
                    assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
                });
    }

    @Test
    void theTransitionIsAudited() {
        Instant now = Instant.now();
        subscriptionRepository.saveAndFlush(
                Subscription.startTrial(organizationId, now.minus(Duration.ofDays(20))));

        subscriptionService.expireTrials(now);

        assertThat(auditRepository.findAll().stream()
                .filter(event -> event.getOrganizationId().equals(organizationId))
                .map(event -> event.getAction()))
                .contains(AuditAction.SUBSCRIPTION_PLAN_CHANGED);
    }

    @Test
    void aTrialThatHasNotExpiredIsLeftAlone() {
        Instant now = Instant.now();
        subscriptionRepository.saveAndFlush(Subscription.startTrial(organizationId, now));

        subscriptionService.expireTrials(now);

        assertThat(subscriptionRepository.findByOrganizationId(organizationId)).get()
                .extracting(Subscription::getStatus)
                .isEqualTo(SubscriptionStatus.TRIALING);
    }

    @Test
    void aPaidSubscriptionIsNeverSweptToFree() {
        // The load-bearing one. A paying customer falling to FREE would silently stop
        // enforcing every hard freeze they have, because FREE cannot carry one (D-33) --
        // a billing event un-freezing production, which is exactly what D-21 forbids.
        Instant now = Instant.now();
        Subscription paid = Subscription.startTrial(organizationId, now.minus(Duration.ofDays(20)));
        paid.activate(Plan.GROWTH, "cus_test", "sub_test", now.plus(Duration.ofDays(30)));
        subscriptionRepository.saveAndFlush(paid);

        subscriptionService.expireTrials(now);

        assertThat(subscriptionRepository.findByOrganizationId(organizationId)).get()
                .satisfies(subscription -> {
                    assertThat(subscription.getPlan()).isEqualTo(Plan.GROWTH);
                    assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
                });
    }
}
