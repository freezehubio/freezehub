package com.freezhub.notification;

import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.RestrictionLevel;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;

/**
 * The words an announcement uses.
 *
 * <p>Separate from any channel so every channel says the same thing, and so the wording
 * can be asserted without sending anything.
 *
 * <p>Times are rendered in **UTC and labelled as such**. A freeze announcement goes to a
 * distributed audience with no single local zone, and an unlabelled time would be read
 * differently by everyone who saw it (01-domain.md invariants 9 and 10).
 */
public final class NotificationMessage {

    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm").withZone(ZoneOffset.UTC);

    private NotificationMessage() {
    }

    /** A single line summarising what happened, suitable as a heading. */
    public static String headline(NotificationEvent event, ChangeRestriction restriction) {
        String blocking = restriction.getLevel() == RestrictionLevel.HARD_FREEZE
                ? "Deployments are blocked"
                : "Advisory";

        return switch (event) {
            case SCHEDULED -> "Deployment freeze scheduled: " + restriction.getName();
            case STARTING_SOON -> "Deployment freeze starts soon: " + restriction.getName()
                    + " — " + blocking + " from " + UTC.format(restriction.getStartsAt()) + " UTC";
            case ACTIVATED -> "Deployment freeze is now active: " + restriction.getName()
                    + " — " + blocking;
            case COMPLETED -> "Deployment freeze finished: " + restriction.getName();
            case CANCELLED -> "Deployment freeze cancelled: " + restriction.getName();
        };
    }


    /**
     * One instant, rendered the way every announcement renders one (FZ-186).
     *
     * <p>Exposed so a channel that lays the facts out itself — Slack's Block Kit puts the
     * start and the end in separate fields — does not grow a second formatter that could
     * drift from this one. Invariants 9 and 10 make UTC a domain rule, not a preference.
     */
    public static String formatUtc(java.time.Instant instant) {
        return UTC.format(instant) + " UTC";
    }

    /**
     * What the level means for somebody about to deploy, rather than its enum name.
     *
     * <p>Event-aware, because the consequence is in the present tense and a finished
     * freeze has none. "Hard freeze — deployments blocked" on a CANCELLED announcement
     * contradicts the same message's own "this no longer applies", and the reader has to
     * work out which half to believe.
     */
    public static String level(NotificationEvent event, ChangeRestriction restriction) {
        boolean hard = restriction.getLevel() == RestrictionLevel.HARD_FREEZE;
        boolean over = event == NotificationEvent.CANCELLED || event == NotificationEvent.COMPLETED;

        if (over) {
            return hard ? "Hard freeze" : "Advisory";
        }
        return hard ? "Hard freeze — deployments blocked" : "Advisory — deployments allowed";
    }

    /**
     * The line that tells the reader what to do about it.
     *
     * <p>Distinct from the headline, which says what happened. An announcement nobody can
     * act on is a notification in the pejorative sense.
     */
    public static String guidance(NotificationEvent event, ChangeRestriction restriction) {
        boolean blocking = restriction.getLevel() == RestrictionLevel.HARD_FREEZE;
        return switch (event) {
            case SCHEDULED -> blocking
                    ? "Nothing is blocked yet. Deployments will be refused once it begins."
                    : "Nothing changes for your pipelines; this one advises rather than blocks.";
            case STARTING_SOON -> "Merge or deploy anything you need to before it begins.";
            case ACTIVATED -> blocking
                    ? "Pipelines asking the policy API will now receive BLOCK."
                    : "Deployments are still allowed. The reason is printed in the build log.";
            case COMPLETED -> "Deployments have resumed.";
            case CANCELLED -> "This restriction no longer applies.";
        };
    }

    /** The full plain-text announcement. */
    public static String body(NotificationEvent event, ChangeRestriction restriction) {
        StringBuilder text = new StringBuilder(headline(event, restriction));

        text.append("\nReason: ").append(restriction.getReason());
        if (restriction.getDescription() != null && !restriction.getDescription().isBlank()) {
            text.append("\n").append(restriction.getDescription());
        }
        text.append("\nWindow: ")
                .append(UTC.format(restriction.getStartsAt()))
                .append(" to ")
                .append(UTC.format(restriction.getEndsAt()))
                .append(" UTC");

        if (event == NotificationEvent.CANCELLED) {
            text.append("\nThis restriction no longer applies.");
        }
        if (event == NotificationEvent.STARTING_SOON) {
            // The point of warning ahead: there is still time to act on it.
            text.append("\nMerge or deploy anything you need to before it begins.");
        }

        return text.toString();
    }

}
