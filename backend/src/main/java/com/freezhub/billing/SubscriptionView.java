package com.freezhub.billing;

import com.freezhub.subscription.Plan;
import com.freezhub.subscription.Subscription;
import com.freezhub.subscription.SubscriptionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What an organization is on, and how close it is to its limits (FZ-085).
 *
 * <p>Every number here is computed on the server. The UI renders it and decides nothing:
 * a limit the frontend worked out for itself would be a second source of truth for
 * entitlement, and the first thing to disagree with the thing that actually refuses.
 *
 * <p>Readable by <strong>any member</strong>, not only administrators. The trial banner
 * has to reach everyone — the person who notices a trial ending is rarely the person who
 * signs — and a member who cannot see why a creation was refused just files a bug.
 */
public record SubscriptionView(
        Plan plan,
        SubscriptionStatus status,
        boolean canUpgradeSelfServe,
        /**
         * Whether this plan's freezes actually block a deployment (FZ-146, D-33).
         *
         * <p>A component rather than something the UI derives from the plan's name: the
         * frontend must not decide entitlement, and this is the one capability that
         * separates FREE from every paid plan.
         */
        boolean blocksDeployments,
        boolean hasBillingAccount,
        Instant trialEndsAt,
        Long trialDaysRemaining,
        Instant currentPeriodEndsAt,
        List<Usage> usage
) {

    /**
     * Shown before a limit is hit, not only when a creation is refused.
     *
     * <p>{@code percentUsed} and {@code atLimit} are components rather than derived
     * methods: Jackson serialises a record's components, and a plain accessor added
     * alongside them is silently left out of the JSON — which is how an earlier version of
     * this sent a usage bar with no percentage in it.
     */
    public record Usage(String resource, long current, Integer limit, Integer percentUsed, boolean atLimit) {

        static Usage of(String resource, long current, Integer limit) {
            // Null limit means unlimited, and unlimited has no percentage. Zero would
            // render as "none used" rather than "no ceiling", which is the opposite.
            Integer percent = (limit == null || limit == 0)
                    ? null
                    : (int) Math.min(100, Math.round(current * 100.0 / limit));
            return new Usage(resource, current, limit, percent, limit != null && current >= limit);
        }
    }

    static SubscriptionView of(Subscription subscription, long applications, long apiKeys,
                               long destinations, Instant now) {
        Plan plan = subscription.getPlan();
        return new SubscriptionView(
                plan,
                subscription.getStatus(),
                plan.isSelfServe() && subscription.getStatus() != SubscriptionStatus.CANCELLED,
                plan.hardFreeze(),
                subscription.getStripeCustomerId() != null,
                subscription.getTrialEndsAt(),
                trialDaysRemaining(subscription, now),
                subscription.getCurrentPeriodEndsAt(),
                List.of(
                        Usage.of("applications", applications, subscription.applicationLimit()),
                        Usage.of("apiKeys", apiKeys, plan.apiKeys()),
                        Usage.of("notificationDestinations", destinations, plan.notificationDestinations())));
    }

    /**
     * Whole days left, floored, and never negative.
     *
     * <p>Null unless the organization is actually trialing: an expired trial that has been
     * suspended should say so through {@code status}, not through a countdown at zero.
     */
    private static Long trialDaysRemaining(Subscription subscription, Instant now) {
        if (subscription.getStatus() != SubscriptionStatus.TRIALING || subscription.getTrialEndsAt() == null) {
            return null;
        }
        long days = Duration.between(now, subscription.getTrialEndsAt()).toDays();
        return Math.max(0, days);
    }
}
