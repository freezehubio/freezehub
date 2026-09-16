package com.freezhub.subscription;

import com.freezhub.audit.AuditAction;
import com.freezhub.audit.AuditActor;
import com.freezhub.audit.AuditResourceType;
import com.freezhub.audit.AuditTrail;
import java.time.Instant;
import java.util.List;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entitlement: what an organization may do, and whether it may do anything (FZ-081).
 *
 * <p>Every limit is checked <strong>on creation only</strong> ({@code D-22}). A downgrade
 * below current usage deletes nothing, disables nothing and hides nothing — it refuses the
 * next creation and no more. Removing applications on a downgrade would block their
 * pipelines, since an unregistered application is refused ({@code D-14}); detaching them
 * from restriction scopes would silently narrow every freeze that named them, which is a
 * billing event un-freezing production.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository subscriptions;
    private final AuditTrail auditTrail;

    public SubscriptionService(SubscriptionRepository subscriptions, AuditTrail auditTrail) {
        this.subscriptions = subscriptions;
        this.auditTrail = auditTrail;
    }

    /**
     * The organization's subscription.
     *
     * <p>A missing row is a bug, not a state, and is logged as one. What it is not is a
     * reason to stop the customer working: falling back to {@link Subscription#unbilled}
     * keeps them running and costs revenue, where the alternative makes their API
     * read-only because of a defect in our billing data. That is the same direction
     * {@code D-21} takes everywhere else — never break the customer over a commercial
     * state — and the same one the backfill migration takes.
     *
     * <p>The warning is what makes it findable. Silence here would mean an organization
     * quietly on unlimited for ever.
     */
    @Transactional(readOnly = true)
    public Subscription of(Long organizationId) {
        return subscriptions.findByOrganizationId(organizationId).orElseGet(() -> {
            log.warn("Organization {} has no subscription row; treating it as unbilled and "
                    + "unlimited. This is a defect - every organization should have one.",
                    organizationId);
            return Subscription.unbilled(organizationId);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Subscription startTrial(Long organizationId, AuditActor actor, Instant now) {
        Subscription subscription = subscriptions.save(Subscription.startTrial(organizationId, now));
        auditTrail.record(organizationId, actor, AuditAction.SUBSCRIPTION_STARTED,
                AuditResourceType.ORGANIZATION, organizationId);
        return subscription;
    }

    /**
     * Refuses the write if the organization may not make one.
     *
     * <p>Called by the filter rather than by each service, so a new endpoint is covered the
     * day it is written instead of the day somebody remembers.
     */
    @Transactional(readOnly = true)
    public void requireWritable(Long organizationId) {
        Subscription subscription = of(organizationId);
        if (!subscription.allowsWrites()) {
            throw new OrganizationSuspendedException(subscription.getStatus());
        }
    }

    @Transactional(readOnly = true)
    public boolean allowsNotifications(Long organizationId) {
        return of(organizationId).allowsNotifications();
    }

    /**
     * Refuses to register one more application than the plan allows.
     *
     * <p>The count is taken inside the caller's transaction, which is what makes it
     * meaningful: two concurrent creations at the limit would otherwise both read nine.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireApplicationHeadroom(Long organizationId, LongSupplier currentCount) {
        Subscription subscription = of(organizationId);
        enforce(subscription.getPlan(), "applications", subscription.applicationLimit(), currentCount);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireApiKeyHeadroom(Long organizationId, LongSupplier currentCount) {
        Subscription subscription = of(organizationId);
        enforce(subscription.getPlan(), "API keys", subscription.getPlan().apiKeys(), currentCount);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireDestinationHeadroom(Long organizationId, LongSupplier currentCount) {
        Subscription subscription = of(organizationId);
        enforce(subscription.getPlan(), "notification destinations",
                subscription.getPlan().notificationDestinations(), currentCount);
    }

    /**
     * Refuses a retention longer than the plan allows (FZ-085, closes {@code OI-14}).
     *
     * <p>Retention is priced per tier in {@code 11-commercial.md} and {@link Plan} has
     * carried the number since {@code FZ-081} — but nothing could set it, so every
     * organization sat on the 365-day default whatever they paid. A row of the pricing
     * table was fiction.
     *
     * <p>Answered as {@code 402} like any other plan limit: the request is well-formed and
     * the caller is permitted, and it is the plan that refuses.
     */
    @Transactional(readOnly = true)
    public void requireRetentionAllowed(Long organizationId, int requestedDays) {
        Plan plan = of(organizationId).getPlan();
        if (requestedDays > plan.deploymentCheckRetentionDays()) {
            throw new PlanLimitExceededException(plan, "days of deployment-check retention",
                    plan.deploymentCheckRetentionDays(), requestedDays);
        }
    }

    /**
     * Refuses a blocking freeze on a plan that does not carry the capability (FZ-143, D-33).
     *
     * <p>The gate is here, at creation, and not in policy evaluation. A FREE organization
     * therefore never has a HARD_FREEZE row, which is what keeps domain rule 3
     * unconditionally true and keeps every billing state out of a policy answer (D-21).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireHardFreezeAllowed(Long organizationId) {
        Plan plan = of(organizationId).getPlan();
        if (!plan.hardFreeze()) {
            throw new PlanFeatureUnavailableException(plan, "blocking freezes");
        }
    }

    /** Null is unlimited, and unlimited never counts — the query is not even run. */
    private void enforce(Plan plan, String resource, Integer limit, LongSupplier currentCount) {
        if (limit == null) {
            return;
        }
        long current = currentCount.getAsLong();
        if (current >= limit) {
            throw new PlanLimitExceededException(plan, resource, limit, current);
        }
    }

    /**
     * Moves trials that have run out onto the free plan (FZ-144, D-33).
     *
     * <p>Was {@code suspendExpiredTrials}. A trial that ends in a read-only account loses
     * the customer; one that ends in a working free tier keeps a live account still
     * announcing freezes, and still telling the engineer on every deploy what a paid plan
     * would have done.
     *
     * <p><strong>Only a trial is swept.</strong> {@code findExpiredTrials} filters on
     * {@code TRIALING}, so a paying subscription cannot be dragged here — and it must not
     * be. A paying customer on FREE would stop enforcing every hard freeze they have,
     * because FREE cannot carry one, which is a billing event un-freezing production
     * ({@code D-21}). Suspension remains where non-payment goes.
     *
     * <p>Follows the lifecycle reconciler's shape: an explicit clock so it can be tested
     * without waiting fourteen days, and a separate thin scheduler that calls it.
     */
    @Transactional
    public int expireTrials(Instant now) {
        List<Subscription> expired = subscriptions.findExpiredTrials(now);
        for (Subscription subscription : expired) {
            subscription.expireToFree();
            auditTrail.record(subscription.getOrganizationId(), AuditActor.system(),
                    AuditAction.SUBSCRIPTION_PLAN_CHANGED, AuditResourceType.ORGANIZATION,
                    subscription.getOrganizationId());
            log.info("Trial expired for organization {}; moved to the FREE plan",
                    subscription.getOrganizationId());
        }
        return expired.size();
    }
}
