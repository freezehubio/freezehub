package com.freezhub.subscription;

/**
 * What an organization is entitled to (FZ-081, pricing in {@code 11-commercial.md}).
 *
 * <p>An enum with limits as constants, not a table. Limits are product decisions that ship
 * with the code and are stated on a public pricing page; a table invites per-customer
 * edits that then contradict it, discovered by a customer rather than by us. Enterprise is
 * the exception, and it gets a nullable override on the subscription row rather than a
 * second mechanism.
 *
 * <p><strong>{@code null} means unlimited</strong>, deliberately rather than a large
 * number: {@code Integer.MAX_VALUE} would read as "2147483647 applications" in an error
 * body and in the UI, and a sentinel that has to be recognised to be printed correctly is
 * one nobody remembers to recognise.
 *
 * <p>Note what is <em>not</em> here. Users are unlimited on every plan, because charging
 * per person told about a freeze is charging for the product (decision {@code D-20}).
 * Policy evaluations are unlimited on every plan, because metering them gives a customer a
 * financial reason to call the API less, which is how a deployment gets past a freeze.
 * Neither is an oversight and neither should acquire a limit.
 */
public enum Plan {

    /** Full-featured for 14 days. A trial that hides the audit trail hides the product. */
    TRIAL(50, 25, null, 365),

    /**
     * Announces freezes; cannot create one that blocks (D-33).
     *
     * <p>The gate is the level at creation, not the answer: a FREE organization never has a
     * HARD_FREEZE row, so domain rule 3 stays unconditionally true and {@code PolicyService}
     * consults no subscription. Seven-day check retention is what keeps a tier nobody pays
     * for cheap to serve.
     */
    FREE(5, 1, 1, 7),

    STARTER(10, 5, 3, 90),
    GROWTH(50, 25, null, 365),
    SCALE(200, null, null, 365),

    /** Priced per deal; limits come from the subscription's overrides. */
    ENTERPRISE(null, null, null, 3650);

    private final Integer applications;
    private final Integer apiKeys;
    private final Integer notificationDestinations;
    private final int deploymentCheckRetentionDays;

    Plan(Integer applications, Integer apiKeys, Integer notificationDestinations,
         int deploymentCheckRetentionDays) {
        this.applications = applications;
        this.apiKeys = apiKeys;
        this.notificationDestinations = notificationDestinations;
        this.deploymentCheckRetentionDays = deploymentCheckRetentionDays;
    }

    /** Applications in the catalog — the metric FreezeHub is priced on ({@code D-20}). */
    public Integer applications() {
        return applications;
    }

    public Integer apiKeys() {
        return apiKeys;
    }

    public Integer notificationDestinations() {
        return notificationDestinations;
    }

    /** The longest deployment-check retention this plan may configure. */
    public int deploymentCheckRetentionDays() {
        return deploymentCheckRetentionDays;
    }

    /**
     * Whether this plan may create a {@code HARD_FREEZE}.
     *
     * <p>A capability rather than a count, and deliberately not a limit of zero: "your FREE
     * plan allows 0 blocking freezes, and you are using 0" is a worse sentence than the
     * truth. See {@code PlanFeatureUnavailableException}.
     */
    public boolean hardFreeze() {
        return this != FREE;
    }

    public boolean isSelfServe() {
        return this != ENTERPRISE;
    }
}
