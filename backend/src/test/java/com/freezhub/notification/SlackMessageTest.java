package com.freezhub.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.RestrictionLevel;
import com.freezhub.restriction.RestrictionScopeNames.ScopeNames;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The layout of a Slack announcement (FZ-186).
 *
 * <p>A pure test, because {@link SlackMessage} is a pure function. That is the point of
 * separating it from the sender: this is a channel whose output nobody looks at until it
 * is already in front of a customer's engineers, so the layout has to be assertable
 * without posting anything.
 */
class SlackMessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Instant STARTS = Instant.parse("2026-11-28T18:00:00Z");
    private static final Instant ENDS = Instant.parse("2026-12-01T06:00:00Z");

    private static ChangeRestriction restriction(RestrictionLevel level) {
        return new ChangeRestriction(1L, "Black Friday Freeze", null, "Peak trading period",
                level, STARTS, ENDS, 1L, Set.of(), Set.of(), Set.of());
    }

    private static ScopeNames scope(List<String> applications, List<String> environments) {
        return new ScopeNames(List.of(), applications, environments);
    }

    private static JsonNode parse(NotificationEvent event, ChangeRestriction restriction,
                                  ScopeNames scope, String appUrl) {
        return MAPPER.readTree(SlackMessage.payload(event, restriction, 42L, scope, appUrl));
    }

    private static String fieldsText(JsonNode payload) {
        StringBuilder all = new StringBuilder();
        payload.path("attachments").get(0).path("blocks").forEach(block ->
                block.path("fields").forEach(field -> all.append(field.path("text").asText()).append("\n")));
        return all.toString();
    }

    private static String blocksText(JsonNode payload) {
        return payload.path("attachments").get(0).path("blocks").toString();
    }

    /**
     * Blocks are invisible to a push notification, the sidebar preview and a screen
     * reader. Without `text` the message arrives on a phone as a blank line.
     */
    @ParameterizedTest
    @EnumSource(NotificationEvent.class)
    void everyEventCarriesAPlainTextFallback(NotificationEvent event) {
        JsonNode payload = parse(event, restriction(RestrictionLevel.HARD_FREEZE),
                scope(List.of(), List.of()), null);

        assertThat(payload.path("text").asText()).isNotBlank().contains("Black Friday Freeze");
    }

    @ParameterizedTest
    @EnumSource(NotificationEvent.class)
    void everyEventIsLaidOutRatherThanLeftPlain(NotificationEvent event) {
        JsonNode payload = parse(event, restriction(RestrictionLevel.HARD_FREEZE),
                scope(List.of("checkout-api"), List.of("production")), null);

        JsonNode attachment = payload.path("attachments").get(0);
        assertThat(attachment.path("color").asText()).startsWith("#");

        JsonNode blocks = attachment.path("blocks");
        assertThat(blocks.get(0).path("type").asText()).isEqualTo("header");
        assertThat(blocks.get(0).path("text").path("text").asText()).isNotBlank();
        // Facts and guidance, on every event — this is the regression the operator
        // reported: one event was formatted and the rest arrived plain.
        assertThat(fieldsText(payload)).contains("Level").contains("From").contains("Until");
        assertThat(blocksText(payload)).contains("context");
    }

    /** Colour is read before any word is, so it tracks consequence rather than level. */
    @Test
    void aLiveHardFreezeIsRedAndACancelledOneIsNot() {
        String active = parse(NotificationEvent.ACTIVATED, restriction(RestrictionLevel.HARD_FREEZE),
                scope(List.of(), List.of()), null).path("attachments").get(0).path("color").asText();
        String cancelled = parse(NotificationEvent.CANCELLED, restriction(RestrictionLevel.HARD_FREEZE),
                scope(List.of(), List.of()), null).path("attachments").get(0).path("color").asText();
        String finished = parse(NotificationEvent.COMPLETED, restriction(RestrictionLevel.HARD_FREEZE),
                scope(List.of(), List.of()), null).path("attachments").get(0).path("color").asText();

        assertThat(active).isEqualTo("#C0392B");
        assertThat(cancelled).isNotEqualTo(active);
        assertThat(finished).isNotEqualTo(active);
    }

    @Test
    void anAdvisoryDoesNotAnnounceItselfAsABlock() {
        JsonNode payload = parse(NotificationEvent.ACTIVATED, restriction(RestrictionLevel.ADVISORY),
                scope(List.of(), List.of()), null);

        assertThat(payload.path("attachments").get(0).path("blocks").get(0)
                .path("text").path("text").asText()).doesNotContain("blocked");
        assertThat(fieldsText(payload)).contains("deployments allowed");
    }

    /**
     * The rule most likely to be got wrong, and the most damaging if it is: an empty
     * dimension is a wildcard. Rendered blank it would read as "covers nothing", which is
     * the exact inverse of "covers everything".
     */
    @Test
    void anEmptyScopeDimensionReadsAsEverythingRatherThanAsNothing() {
        JsonNode payload = parse(NotificationEvent.ACTIVATED, restriction(RestrictionLevel.HARD_FREEZE),
                scope(List.of(), List.of("production")), null);

        String fields = fieldsText(payload);
        assertThat(fields).contains("All applications");
        assertThat(fields).contains("production");
    }

    @Test
    void namedScopeIsListed() {
        JsonNode payload = parse(NotificationEvent.ACTIVATED, restriction(RestrictionLevel.HARD_FREEZE),
                scope(List.of("checkout-api", "payments-api"), List.of("production")), null);

        assertThat(fieldsText(payload)).contains("checkout-api, payments-api");
    }

    /** A team dimension nobody set would be a "All teams" row on every single message. */
    @Test
    void teamsAppearOnlyWhenScoped() {
        JsonNode without = parse(NotificationEvent.ACTIVATED, restriction(RestrictionLevel.HARD_FREEZE),
                scope(List.of(), List.of()), null);
        assertThat(fieldsText(without)).doesNotContain("Teams");

        JsonNode with = parse(NotificationEvent.ACTIVATED, restriction(RestrictionLevel.HARD_FREEZE),
                new ScopeNames(List.of("platform"), List.of(), List.of()), null);
        assertThat(fieldsText(with)).contains("Teams").contains("platform");
    }

    /** A dead link in an announcement is worse than none: it is clicked during an incident. */
    @Test
    void theButtonAppearsOnlyWhenTheAppUrlIsKnown() {
        assertThat(blocksText(parse(NotificationEvent.ACTIVATED, restriction(RestrictionLevel.HARD_FREEZE),
                scope(List.of(), List.of()), null))).doesNotContain("View in FreezeHub");

        assertThat(blocksText(parse(NotificationEvent.ACTIVATED, restriction(RestrictionLevel.HARD_FREEZE),
                scope(List.of(), List.of()), "")))
                .doesNotContain("View in FreezeHub");

        assertThat(blocksText(parse(NotificationEvent.ACTIVATED, restriction(RestrictionLevel.HARD_FREEZE),
                scope(List.of(), List.of()), "https://app.freezehub.io/")))
                .contains("View in FreezeHub")
                // One slash, whether or not the configured value ends in one.
                .contains("https://app.freezehub.io/restrictions/42");
    }

    /** Customer input, rendered as mrkdwn. Slack reserves exactly these three. */
    @Test
    void whatTheCustomerTypedIsEscaped() {
        ChangeRestriction awkward = new ChangeRestriction(1L, "Q4 <freeze> & hold", null,
                "Because 1 < 2 & we said so", RestrictionLevel.HARD_FREEZE, STARTS, ENDS, 1L,
                Set.of(), Set.of(), Set.of());

        String blocks = blocksText(parse(NotificationEvent.ACTIVATED, awkward,
                scope(List.of(), List.of()), null));

        assertThat(blocks).contains("Q4 &lt;freeze&gt; &amp; hold");
        assertThat(blocks).doesNotContain("<freeze>");
    }

    /** Slack cuts a header at 150 characters; being cut mid-word looks like a bug. */
    @Test
    void anOverlongHeaderIsTruncatedDeliberately() {
        ChangeRestriction longName = new ChangeRestriction(1L, "x".repeat(400), null, "Reason",
                RestrictionLevel.HARD_FREEZE, STARTS, ENDS, 1L, Set.of(), Set.of(), Set.of());

        String header = parse(NotificationEvent.ACTIVATED, longName, scope(List.of(), List.of()), null)
                .path("attachments").get(0).path("blocks").get(0).path("text").path("text").asText();

        assertThat(header.length()).isLessThanOrEqualTo(150);
    }


    /**
     * A finished freeze has no present-tense consequence. Saying "deployments blocked" on
     * a cancellation contradicts the same message's own "this no longer applies", and the
     * reader has to decide which half to believe.
     */
    @Test
    void aFinishedFreezeDoesNotStillClaimToBlock() {
        for (NotificationEvent over : List.of(NotificationEvent.CANCELLED, NotificationEvent.COMPLETED)) {
            String fields = fieldsText(parse(over, restriction(RestrictionLevel.HARD_FREEZE),
                    scope(List.of(), List.of()), null));
            assertThat(fields).contains("Hard freeze").doesNotContain("deployments blocked");
        }
    }

    @Test
    void timesAreUtcAndSaySo() {
        String fields = fieldsText(parse(NotificationEvent.ACTIVATED,
                restriction(RestrictionLevel.HARD_FREEZE), scope(List.of(), List.of()), null));

        assertThat(fields).contains("28 Nov 2026 18:00 UTC");
        assertThat(fields).contains("1 Dec 2026 06:00 UTC");
    }

}
