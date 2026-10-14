package com.freezhub.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The self-serve ladder has a shape, and the shape is the product decision (`FZ-231`).
 *
 * <p>These are not tests of arithmetic. They encode why {@code TEAM} was added: the ladder
 * went 10 applications to 50 with nothing between, so a customer with twenty paid Growth's
 * $349 — about $17 an application against Starter's $9.90. Paying <em>more</em> per unit for
 * growing into a tier is how a deal stalls, and nothing in the code would have noticed.
 */
class PlanLadderTest {

    /** Self-serve only. ENTERPRISE is priced per deal and TRIAL is not sold. */
    private static final List<Plan> LADDER = List.of(Plan.FREE, Plan.STARTER, Plan.TEAM, Plan.GROWTH, Plan.SCALE);

    /** Monthly list price, in whole dollars, as published on the pricing page. */
    private static int priceOf(Plan plan) {
        return switch (plan) {
            case FREE -> 0;
            case STARTER -> 99;
            case TEAM -> 199;
            case GROWTH -> 349;
            case SCALE -> 899;
            default -> throw new IllegalArgumentException(plan + " is not on the self-serve ladder");
        };
    }

    @Test
    @DisplayName("no limit ever shrinks as the ladder goes up")
    void limitsNeverShrink() {
        for (int i = 1; i < LADDER.size(); i++) {
            Plan lower = LADDER.get(i - 1);
            Plan upper = LADDER.get(i);

            assertThat(atLeast(upper.applications(), lower.applications()))
                    .as("%s must allow at least as many applications as %s", upper, lower).isTrue();
            assertThat(atLeast(upper.apiKeys(), lower.apiKeys()))
                    .as("%s must allow at least as many API keys as %s", upper, lower).isTrue();
            assertThat(atLeast(upper.notificationDestinations(), lower.notificationDestinations()))
                    .as("%s must allow at least as many destinations as %s", upper, lower).isTrue();
            assertThat(upper.deploymentCheckRetentionDays())
                    .as("%s must retain checks at least as long as %s", upper, lower)
                    .isGreaterThanOrEqualTo(lower.deploymentCheckRetentionDays());
        }
    }

    @Test
    @DisplayName("no gap in the ladder is wide enough to hide a customer")
    void noTierMoreThanQuadruplesTheOneBelow() {
        /*
         * The defect TEAM actually fixed, and the one the price-per-application test below
         * does NOT catch: at the tier caps the ladder was fine — Starter $9.90, Growth $6.98
         * — but a customer with twenty applications could not buy at Starter's rate. They
         * paid Growth's $349 for twenty, which is $17.45. The hole was the 5x jump from 10
         * to 50, not the prices at either end.
         *
         * Four, not three, because GROWTH to SCALE is already 4x (50 to 200). That is the
         * same defect one size smaller and the next candidate if customers stall there; it
         * is tolerated here rather than silently blessed.
         */
        List<Plan> bounded = LADDER.stream().filter(p -> p.applications() != null).toList();

        for (int i = 1; i < bounded.size(); i++) {
            int lower = bounded.get(i - 1).applications();
            int upper = bounded.get(i).applications();

            assertThat((double) upper / lower)
                    .as("%s allows %d applications and %s allows %d — a customer in between "
                            + "pays the upper tier's price at the lower tier's usage",
                            bounded.get(i - 1), lower, bounded.get(i), upper)
                    .isLessThanOrEqualTo(4.0);
        }
    }

    @Test
    @DisplayName("every step up the ladder lowers the price per application")
    void pricePerApplicationFalls() {
        // The rule TEAM exists to restore. A tier that costs more per application than the
        // one below it punishes the customer for growing, which is the opposite of what a
        // self-serve ladder is for.
        List<Plan> paid = LADDER.stream().filter(p -> priceOf(p) > 0).toList();

        for (int i = 1; i < paid.size(); i++) {
            double lower = (double) priceOf(paid.get(i - 1)) / paid.get(i - 1).applications();
            double upper = (double) priceOf(paid.get(i)) / paid.get(i).applications();

            assertThat(upper)
                    .as("%s is $%.2f/application, which is dearer than %s at $%.2f",
                            paid.get(i), upper, paid.get(i - 1), lower)
                    .isLessThan(lower);
        }
    }

    @Test
    @DisplayName("only FREE announces without blocking")
    void onlyFreeIsAdvisoryOnly() {
        // D-33. If this ever fails for another plan, the upsell line in PolicyService and the
        // entire Free-to-paid conversion story have moved with it.
        for (Plan plan : Plan.values()) {
            assertThat(plan.hardFreeze())
                    .as("%s hardFreeze()", plan)
                    .isEqualTo(plan != Plan.FREE);
        }
    }

    private static boolean atLeast(Integer upper, Integer lower) {
        if (upper == null) return true;        // unlimited beats everything
        if (lower == null) return false;       // but a bounded tier may not sit above unlimited
        return upper >= lower;
    }
}
