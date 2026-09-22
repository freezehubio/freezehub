package com.freezhub.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.freezhub.integration.Integration;
import com.freezhub.integration.IntegrationType;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.RestrictionLevel;
import com.freezhub.restriction.RestrictionScopeNames;
import java.time.Instant;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The Slack HTTP call itself (FZ-041).
 *
 * <p>No Spring context: the sender is constructed directly with a builder bound to
 * {@link MockRestServiceServer}, so the request it actually produces — method, URL, body —
 * is asserted rather than a stub standing in for it. (Binding after the application has
 * already built its client would have had no effect, which is why this is separate from
 * the dispatcher's test.)
 */
class SlackNotificationSenderTest {

    private static final String WEBHOOK = "https://hooks.slack.com/services/T0/B0/SECRETPATHVALUE";

    private MockRestServiceServer slack;
    private SlackNotificationSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        slack = MockRestServiceServer.bindTo(builder).build();
        // The sender takes the built, guarded client now (FZ-126); MockRestServiceServer
        // still binds to the builder it came from.
        /*
         * Null repositories are safe here and only here: every restriction in this class
         * has an empty scope, and RestrictionScopeNames short-circuits a wildcard
         * dimension without querying. Give one of these restrictions a scope and this
         * will fail loudly rather than quietly, which is the right way round.
         *
         * The layout itself is asserted in SlackMessageTest, which needs none of this.
         */
        RestrictionScopeNames scopeNames = new RestrictionScopeNames(null, null, null);
        sender = new SlackNotificationSender(builder.build(), scopeNames, "https://app.freezehub.test");
    }

    private Integration destination(String config) {
        return new Integration(1L, IntegrationType.SLACK, config);
    }

    private ChangeRestriction restriction() {
        return new ChangeRestriction(
                1L, "Black Friday Freeze", null, "Revenue-critical period",
                RestrictionLevel.HARD_FREEZE,
                Instant.parse("2026-11-27T14:00:00Z"), Instant.parse("2026-12-02T09:30:00Z"),
                1L, Set.of(), Set.of(), Set.of());
    }

    private Notification notification() {
        return new Notification(1L, 1L, 1L, NotificationEvent.ACTIVATED);
    }

    @Test
    void postsTheAnnouncementToTheConfiguredWebhook() {
        slack.expect(requestTo(WEBHOOK))
                .andExpect(method(HttpMethod.POST))
                // `text` stays, as the fallback a push notification shows (FZ-186).
                .andExpect(content().string(Matchers.containsString("\"text\"")))
                .andExpect(content().string(Matchers.containsString("Revenue-critical period")))
                .andExpect(content().string(Matchers.containsString("UTC")))
                // ...and it is laid out rather than plain.
                .andExpect(content().string(Matchers.containsString("\"blocks\"")))
                .andExpect(content().string(Matchers.containsString("\"color\"")))
                .andExpect(content().string(Matchers.containsString("View in FreezeHub")))
                .andRespond(withSuccess());

        sender.send(notification(), restriction(), destination("{\"webhookUrl\":\"" + WEBHOOK + "\"}"));

        slack.verify();
    }

    @Test
    void failsWhenSlackRejectsTheRequest() {
        slack.expect(requestTo(WEBHOOK)).andRespond(withServerError());

        assertThatThrownBy(() -> sender.send(
                notification(), restriction(), destination("{\"webhookUrl\":\"" + WEBHOOK + "\"}")))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    @Test
    void neverPutsTheWebhookUrlInTheFailureMessage() {
        // This message is stored in notification.last_error and read by whoever diagnoses
        // a missing announcement. The webhook URL is a bearer credential, and most HTTP
        // client exceptions include the request URI by default.
        slack.expect(requestTo(WEBHOOK)).andRespond(withServerError());

        assertThatThrownBy(() -> sender.send(
                notification(), restriction(), destination("{\"webhookUrl\":\"" + WEBHOOK + "\"}")))
                .hasMessageNotContaining("SECRETPATHVALUE")
                .hasMessageNotContaining("hooks.slack.com");
    }

    @Test
    void failsClearlyWhenTheConfigHasNoWebhookUrl() {
        assertThatThrownBy(() -> sender.send(notification(), restriction(), destination("{}")))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("no webhookUrl");
    }

    @Test
    void failsClearlyWhenTheConfigIsNotJson() {
        assertThatThrownBy(() -> sender.send(notification(), restriction(), destination("nonsense")))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void handlesTheChannelItDeclares() {
        assertThat(sender.type()).isEqualTo(IntegrationType.SLACK);
    }

}
