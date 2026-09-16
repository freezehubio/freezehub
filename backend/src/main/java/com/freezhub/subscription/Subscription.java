package com.freezhub.subscription;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** One organization's commercial state (FZ-081). Exactly one row per organization. */
@Entity
@Table(name = "subscription")
public class Subscription {

    /** Long enough to schedule a real freeze and watch a pipeline be refused by it. */
    public static final int TRIAL_DAYS = 14;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false, unique = true)
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status;

    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    @Column(name = "application_limit_override")
    private Integer applicationLimitOverride;

    /**
     * The payment provider's identifiers (FZ-084).
     *
     * <p>Stripe is the source of truth for payment; FreezeHub is the source of truth for
     * entitlement. These are how the two are joined, and they are written only from a
     * signature-verified webhook — never from a Checkout redirect, which is a browser
     * navigation anyone can forge.
     */
    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @Column(name = "stripe_subscription_id")
    private String stripeSubscriptionId;

    @Column(name = "current_period_ends_at")
    private Instant currentPeriodEndsAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Subscription() {
    }

    private Subscription(Long organizationId, Plan plan, SubscriptionStatus status, Instant trialEndsAt) {
        this.organizationId = organizationId;
        this.plan = plan;
        this.status = status;
        this.trialEndsAt = storable(trialEndsAt);
    }

    /** A new organization starts a full-featured trial with no card (FZ-082). */
    public static Subscription startTrial(Long organizationId, Instant now) {
        return new Subscription(organizationId, Plan.TRIAL, SubscriptionStatus.TRIALING,
                now.plus(TRIAL_DAYS, ChronoUnit.DAYS));
    }

    /**
     * What an organization with no subscription row is treated as (FZ-081).
     *
     * <p>Never persisted, and it should never be reachable: the column is unique and not
     * null, every organization predating billing was backfilled, and every new one gets a
     * row as it is created. If this is ever returned, something is wrong and the log says
     * so.
     *
     * <p>It grants everything rather than nothing, and that direction is deliberate. The
     * alternative — refusing — would make the human API read-only for a customer because
     * of a bug in <em>our</em> billing data, which is the failure {@code D-21} exists to
     * prevent. The backfill migration makes the same trade in the same direction: a
     * customer who is not billed correctly is a conversation, and a customer whose work
     * stops is an outage.
     */
    static Subscription unbilled(Long organizationId) {
        return new Subscription(organizationId, Plan.ENTERPRISE, SubscriptionStatus.ACTIVE, null);
    }

    /** Provisioned after a demo, on an agreed plan (FZ-086). */
    public static Subscription provisioned(Long organizationId, Plan plan) {
        return new Subscription(organizationId, plan, SubscriptionStatus.ACTIVE, null);
    }

    public Long getId() {
        return id;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Plan getPlan() {
        return plan;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public Instant getTrialEndsAt() {
        return trialEndsAt;
    }

    public Integer getApplicationLimitOverride() {
        return applicationLimitOverride;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * How many applications this organization may register.
     *
     * <p>The override is what makes Enterprise expressible without a second mechanism, and
     * it is read here rather than at each call site so nothing can forget it.
     */
    public Integer applicationLimit() {
        return applicationLimitOverride != null ? applicationLimitOverride : plan.applications();
    }

    public boolean allowsWrites() {
        return status.allowsWrites();
    }

    public boolean allowsNotifications() {
        return status.allowsNotifications();
    }

    /** True once a trial has run out. Never true for a subscription that is not trialing. */
    public boolean trialHasExpired(Instant now) {
        return status == SubscriptionStatus.TRIALING
                && trialEndsAt != null
                && !now.isBefore(trialEndsAt);
    }

    /**
     * Sets this organization's negotiated application limit (FZ-081).
     *
     * <p>Package-private: Enterprise limits come from a deal, so the only things that
     * should set one are operator provisioning ({@code FZ-086}) and the subscription
     * module itself. Nothing reachable from a tenant request may change what a tenant is
     * entitled to.
     */
    void setApplicationLimitOverride(Integer applicationLimitOverride) {
        this.applicationLimitOverride = applicationLimitOverride;
    }

    public String getStripeCustomerId() {
        return stripeCustomerId;
    }

    public String getStripeSubscriptionId() {
        return stripeSubscriptionId;
    }

    public Instant getCurrentPeriodEndsAt() {
        return currentPeriodEndsAt;
    }

    /**
     * A paid subscription began or changed plan (FZ-084).
     *
     * <p>Public because the billing module drives it, and named for what happened rather
     * than for the fields it sets — the caller should not be choosing a status.
     */
    public void activate(Plan plan, String stripeCustomerId, String stripeSubscriptionId,
                         Instant currentPeriodEndsAt) {
        this.plan = plan;
        this.status = SubscriptionStatus.ACTIVE;
        if (stripeCustomerId != null) {
            this.stripeCustomerId = stripeCustomerId;
        }
        if (stripeSubscriptionId != null) {
            this.stripeSubscriptionId = stripeSubscriptionId;
        }
        this.currentPeriodEndsAt = storable(currentPeriodEndsAt);
    }

    /**
     * A payment failed.
     *
     * <p>Still writable, deliberately: dunning is a conversation, and locking an
     * organization out on the first failed charge punishes an expired card.
     */
    public void markPastDue() {
        this.status = SubscriptionStatus.PAST_DUE;
    }

    /** The subscription ended at the provider. */
    public void cancel() {
        this.status = SubscriptionStatus.CANCELLED;
    }

    /**
     * A trial that ran out becomes a working free account (FZ-144, D-33).
     *
     * <p>Not suspension. A trial ending in a read-only account loses the customer; one
     * ending in a free tier keeps a live account still announcing freezes. Suspension is
     * for non-payment by someone who was paying, and nothing here reaches that case.
     */
    void expireToFree() {
        this.plan = Plan.FREE;
        this.status = SubscriptionStatus.ACTIVE;
    }

    void suspend() {
        this.status = SubscriptionStatus.SUSPENDED;
    }

    /** Same reasoning as {@code D-25}: the database keeps microseconds, so nothing else pretends to. */
    private static Instant storable(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
