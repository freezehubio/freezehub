package com.freezhub.subscription;

/**
 * A capability the plan does not carry at all (FZ-143, {@code D-33}).
 *
 * <p>Sibling of {@link PlanLimitExceededException} and answered with the same {@code 402}:
 * the request was well-formed and the caller is permitted, and it is the plan that refused.
 *
 * <p><strong>Separate because a capability is not a count.</strong> Expressing "this plan
 * cannot do that" as a limit of zero would render "the FREE plan allows 0 blocking freezes;
 * this organization has 0" — a sentence that is arithmetically true and tells the reader
 * nothing. It carries no numbers, deliberately, so a UI cannot render a usage bar for
 * something that has no usage.
 */
public class PlanFeatureUnavailableException extends RuntimeException {

    private final Plan plan;
    private final String feature;

    public PlanFeatureUnavailableException(Plan plan, String feature) {
        super("The %s plan does not include %s.".formatted(plan, feature));
        this.plan = plan;
        this.feature = feature;
    }

    public Plan plan() {
        return plan;
    }

    public String feature() {
        return feature;
    }
}
