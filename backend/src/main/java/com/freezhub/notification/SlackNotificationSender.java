package com.freezhub.notification;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.freezhub.integration.Integration;
import com.freezhub.integration.IntegrationType;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.RestrictionScopeNames;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Posts an announcement to a Slack incoming webhook (FZ-041).
 *
 * <p>The webhook URL comes from the integration's config and is used, never logged: it is
 * a bearer credential, and an exception message ends up in {@code notification.last_error}
 * where a support engineer would read it.
 *
 * <p>The message itself is built by {@link SlackMessage} (FZ-186). This class stays what it
 * was: the thing that posts.
 */
@Component
public class SlackNotificationSender implements NotificationSender {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final RestrictionScopeNames scopeNames;

    /**
     * Where this FreezeHub is reachable, for the "View in FreezeHub" button (FZ-186).
     *
     * <p>Empty by default and empty locally, because the backend has never had any reason
     * to know its own front end's address. When it is unset the button is omitted rather
     * than rendered pointing nowhere — a dead link in an announcement is worse than no
     * link, because the reader trusts it enough to click during an incident.
     */
    private final String appUrl;

    /*
     * The guarded client (FZ-126): no redirects, and every destination resolved and checked
     * on the way out. This one calls an address a customer chose, which is what separates
     * it from the demo notifier's own builder.
     */
    public SlackNotificationSender(@Qualifier("outboundDeliveryRestClient") RestClient restClient,
                                   RestrictionScopeNames scopeNames,
                                   @Value("${freezehub.app-url:}") String appUrl) {
        this.restClient = restClient;
        this.scopeNames = scopeNames;
        this.appUrl = appUrl;
    }

    @Override
    public IntegrationType type() {
        return IntegrationType.SLACK;
    }

    @Override
    public void send(Notification notification, ChangeRestriction restriction, Integration destination) {
        String webhookUrl = webhookUrlOf(destination);

        /*
         * Scope is resolved here rather than carried on the notification because this is
         * the only channel that shows it, and because it is safe here: NotificationDelivery
         * calls this inside its transaction with `restriction` in the same persistence
         * context, so the LAZY scope collections initialise rather than throwing.
         */
        String payload = SlackMessage.payload(notification.getEvent(), restriction,
                notification.getRestrictionId(), scopeNames.of(restriction), appUrl);

        try {
            restClient.post()
                    .uri(webhookUrl)
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException failed) {
            // Deliberately not including the exception message verbatim: Spring puts the
            // request URI in it, and that URI is the credential.
            throw new NotificationDeliveryException(
                    "Slack rejected or could not be reached: " + failed.getClass().getSimpleName());
        }
    }

    private String webhookUrlOf(Integration destination) {
        try {
            JsonNode config = MAPPER.readTree(destination.getConfig());
            String url = config.path("webhookUrl").asText("");
            if (url.isBlank()) {
                throw new NotificationDeliveryException("Slack integration has no webhookUrl configured");
            }
            return url;
        } catch (tools.jackson.core.JacksonException unreadable) {
            throw new NotificationDeliveryException("Slack integration config is not valid JSON");
        }
    }

}
