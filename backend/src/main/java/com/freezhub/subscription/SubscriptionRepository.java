package com.freezhub.subscription;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByOrganizationId(Long organizationId);

    /** Both unique, so a webhook resolves to exactly one organization or to none (FZ-084). */
    Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);

    Optional<Subscription> findByStripeCustomerId(String stripeCustomerId);

    /**
     * Trials that have run out.
     *
     * <p>Filtered in the query rather than in Java: the sweep runs over every organization
     * and all but a handful are irrelevant on any given pass.
     */
    @Query("""
            select s from Subscription s
            where s.status = com.freezhub.subscription.SubscriptionStatus.TRIALING
              and s.trialEndsAt is not null
              and s.trialEndsAt <= :now
            """)
    List<Subscription> findExpiredTrials(@Param("now") Instant now);

    /** Removes the trial of an unverified organization being purged (FZ-082). */
    @Modifying
    void deleteByOrganizationId(Long organizationId);

}
