package com.freezhub.notification;

import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.RestrictionLevel;
import com.freezhub.restriction.RestrictionScopeNames.ScopeNames;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * An announcement, laid out for Slack (FZ-186).
 *
 * <p><b>The only class that knows Slack exists.</b> {@link NotificationMessage} is
 * deliberately channel-agnostic — "so every channel says the same thing" — so Block Kit
 * must not leak into it, or email and webhooks would inherit Slack's formatting. The
 * wording still comes from there; this only arranges it.
 *
 * <p>The restriction id comes in separately rather than from {@code restriction.getId()}:
 * the notification is what carries it, it is never null there, and taking it from the
 * entity made the link untestable without persisting one.
 *
 * <p>Pure: takes facts, returns JSON, sends nothing. That is what lets the layout be
 * asserted in a unit test without a webhook, which matters for a channel whose output
 * nobody sees until it is already in front of a customer's engineers.
 *
 * <h2>Why an attachment wraps the blocks</h2>
 *
 * <p>The coloured bar down the left edge is the fastest signal in the message — it is read
 * before any words are. It is only available on an attachment, so the blocks live inside
 * one. Slack calls attachments legacy; there is no replacement that draws that bar.
 *
 * <h2>Why `text` is still set</h2>
 *
 * <p>Blocks do not appear in a push notification, the channel sidebar preview, or to a
 * screen reader. A message with blocks and no `text` arrives on a phone as a blank line.
 */
final class SlackMessage {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Slack truncates a header at 150 characters; truncating deliberately beats being cut. */
    private static final int HEADER_LIMIT = 150;

    /*
     * Colour tracks the consequence, not the level. A cancelled hard freeze is good news
     * and must not arrive looking like a live one — the whole point of the bar is that it
     * is understood before the text is.
     */
    private static final String BLOCKING = "#C0392B";
    private static final String WARNING = "#D9822B";
    private static final String RESOLVED = "#2E7D32";
    private static final String NEUTRAL = "#8A8A8A";

    private SlackMessage() {
    }

    static String payload(NotificationEvent event, ChangeRestriction restriction,
                          Long restrictionId, ScopeNames scope, String appUrl) {
        ObjectNode root = MAPPER.createObjectNode();

        // The fallback, and the only part some clients will ever show.
        root.put("text", NotificationMessage.headline(event, restriction));

        ObjectNode attachment = root.putArray("attachments").addObject();
        attachment.put("color", colourFor(event, restriction));
        ArrayNode blocks = attachment.putArray("blocks");

        header(blocks, headlineFor(event, restriction));
        summary(blocks, restriction);
        facts(blocks, event, restriction, scope);
        if (appUrl != null && !appUrl.isBlank() && restrictionId != null) {
            viewButton(blocks, appUrl, restrictionId);
        }
        context(blocks, NotificationMessage.guidance(event, restriction));

        return root.toString();
    }

    /**
     * A header says what happened in the reader's terms, not the enum's.
     *
     * <p>"Deployments are blocked" rather than "ACTIVATED": the person reading wants to
     * know whether they can ship, and the event name does not answer that on its own.
     */
    private static String headlineFor(NotificationEvent event, ChangeRestriction restriction) {
        boolean blocking = restriction.getLevel() == RestrictionLevel.HARD_FREEZE;
        return switch (event) {
            case SCHEDULED -> blocking ? "Deployment freeze scheduled" : "Advisory scheduled";
            case STARTING_SOON -> blocking ? "Deployment freeze starts soon" : "Advisory starts soon";
            case ACTIVATED -> blocking ? "Deployments are blocked" : "Advisory now in force";
            case COMPLETED -> "Deployment freeze finished";
            case CANCELLED -> "Deployment freeze cancelled";
        };
    }

    private static String colourFor(NotificationEvent event, ChangeRestriction restriction) {
        boolean blocking = restriction.getLevel() == RestrictionLevel.HARD_FREEZE;
        return switch (event) {
            case ACTIVATED -> blocking ? BLOCKING : WARNING;
            case STARTING_SOON -> WARNING;
            case SCHEDULED -> NEUTRAL;
            case COMPLETED -> RESOLVED;
            case CANCELLED -> NEUTRAL;
        };
    }

    private static void header(ArrayNode blocks, String text) {
        ObjectNode block = blocks.addObject();
        block.put("type", "header");
        ObjectNode content = block.putObject("text");
        content.put("type", "plain_text");
        content.put("text", truncate(text, HEADER_LIMIT));
        content.put("emoji", true);
    }

    private static void summary(ArrayNode blocks, ChangeRestriction restriction) {
        StringBuilder text = new StringBuilder("*").append(escape(restriction.getName())).append("*");
        text.append("\n").append(escape(restriction.getReason()));
        if (restriction.getDescription() != null && !restriction.getDescription().isBlank()) {
            text.append("\n").append(escape(restriction.getDescription()));
        }
        section(blocks, text.toString());
    }

    private static void facts(ArrayNode blocks, NotificationEvent event,
                              ChangeRestriction restriction, ScopeNames scope) {
        ObjectNode block = blocks.addObject();
        block.put("type", "section");
        ArrayNode fields = block.putArray("fields");

        field(fields, "Level", NotificationMessage.level(event, restriction));
        field(fields, "Applies to", listOrAll(scope.applications(), "All applications"));
        field(fields, "Environments", listOrAll(scope.environments(), "All environments"));
        if (!scope.teams().isEmpty()) {
            // Only when set. An empty teams dimension adds no constraint, and a field
            // reading "All teams" on every message is noise.
            field(fields, "Teams", listOrAll(scope.teams(), "All teams"));
        }
        field(fields, "From", NotificationMessage.formatUtc(restriction.getStartsAt()));
        field(fields, "Until", NotificationMessage.formatUtc(restriction.getEndsAt()));
    }

    /**
     * An empty dimension is a wildcard, not an empty set (`01-domain.md`).
     *
     * <p>Rendering it blank would invert the rule and turn "every application deploying to
     * production" into a message that looks like it covers nothing.
     */
    private static String listOrAll(List<String> names, String wildcard) {
        return names.isEmpty() ? wildcard : escape(String.join(", ", names));
    }

    private static void viewButton(ArrayNode blocks, String appUrl, Long restrictionId) {
        ObjectNode block = blocks.addObject();
        block.put("type", "actions");
        ObjectNode button = block.putArray("elements").addObject();
        button.put("type", "button");
        ObjectNode label = button.putObject("text");
        label.put("type", "plain_text");
        label.put("text", "View in FreezeHub");
        button.put("url", appUrl.replaceAll("/+$", "") + "/restrictions/" + restrictionId);
    }

    private static void section(ArrayNode blocks, String markdown) {
        ObjectNode block = blocks.addObject();
        block.put("type", "section");
        ObjectNode content = block.putObject("text");
        content.put("type", "mrkdwn");
        content.put("text", markdown);
    }

    private static void context(ArrayNode blocks, String markdown) {
        ObjectNode block = blocks.addObject();
        block.put("type", "context");
        ObjectNode element = block.putArray("elements").addObject();
        element.put("type", "mrkdwn");
        element.put("text", markdown);
    }

    private static void field(ArrayNode fields, String label, String value) {
        ObjectNode field = fields.addObject();
        field.put("type", "mrkdwn");
        field.put("text", "*" + label + "*\n" + value);
    }

    /**
     * Slack's three reserved characters, per its own escaping rules.
     *
     * <p>Applied to everything a customer typed. A restriction named
     * {@code Q4 <freeze> & hold} would otherwise render as a broken tag, and the reader
     * would see a different name from the one in the product.
     */
    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }

}
