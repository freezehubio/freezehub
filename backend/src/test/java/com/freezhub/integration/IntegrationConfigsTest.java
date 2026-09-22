package com.freezhub.integration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * What a destination's configuration must look like (FZ-045, tightened by FZ-189).
 *
 * <p><b>Shape only.</b> None of this is the egress boundary. {@code OI-23} settled that in
 * {@code FZ-125} — "the fix is egress, not validation" — and {@code OutboundAddressPolicy}
 * enforces it at connect time, where a name cannot be moved after the check. These
 * assertions are about an operator being told what is wrong while they are still looking
 * at the field.
 */
class IntegrationConfigsTest {

    private static void validate(IntegrationType type, String config) {
        IntegrationConfigs.validate(type, config);
    }

    @Test
    void acceptsAWellFormedSlackWebhook() {
        assertThatCode(() -> validate(IntegrationType.SLACK,
                "{\"webhookUrl\":\"https://hooks.slack.com/services/T0/B0/xyz\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesAMissingField() {
        assertThatThrownBy(() -> validate(IntegrationType.SLACK, "{}"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("webhookUrl");
    }

    @Test
    void refusesPlainHttp() {
        assertThatThrownBy(() -> validate(IntegrationType.WEBHOOK,
                "{\"url\":\"http://acme.test/hooks\"}"))
                .hasMessageContaining("https");
    }

    /**
     * The scheme check used to be {@code startsWith("https://")}, which
     * {@code https://HTTPS://…} style casing and a bare scheme both slip past. Parsed now.
     */
    @Test
    void acceptsAnUppercaseScheme() {
        assertThatCode(() -> validate(IntegrationType.WEBHOOK,
                "{\"url\":\"HTTPS://acme.test/hooks\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesAUrlWithNoHost() {
        assertThatThrownBy(() -> validate(IntegrationType.WEBHOOK, "{\"url\":\"https:///hooks\"}"))
                .hasMessageContaining("no host");
    }

    /**
     * {@code OI-23} names this exactly: a userinfo authority satisfies a prefix check while
     * resolving somewhere else entirely. Refused on the way out already; refused here too,
     * so the operator learns now rather than from a failed delivery.
     */
    @Test
    void refusesAUserinfoAuthorityAndExplainsWhy() {
        assertThatThrownBy(() -> validate(IntegrationType.SLACK,
                "{\"webhookUrl\":\"https://hooks.slack.com@10.0.0.5/x\"}"))
                .hasMessageContaining("before the host");
    }

    @Test
    void acceptsRecipients() {
        assertThatCode(() -> validate(IntegrationType.EMAIL,
                "{\"recipients\":[\"releases@acme.test\"]}"))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesAnEmptyRecipientList() {
        assertThatThrownBy(() -> validate(IntegrationType.EMAIL, "{\"recipients\":[]}"))
                .hasMessageContaining("recipients");
    }

    @Test
    void refusesConfigThatIsNotJson() {
        assertThatThrownBy(() -> validate(IntegrationType.SLACK, "webhookUrl=whatever"))
                .hasMessageContaining("not valid JSON");
    }

    /** A summary may name the host and must never carry the path, which is the secret. */
    @Test
    void summarisesASlackWebhookWithoutItsPath() {
        String summary = IntegrationConfigs.summarise(IntegrationType.SLACK,
                "{\"webhookUrl\":\"https://hooks.slack.com/services/T0/B0/SECRET\"}");

        assertThatCode(() -> {
            assert summary.contains("hooks.slack.com");
            assert !summary.contains("SECRET");
        }).doesNotThrowAnyException();
    }

}
