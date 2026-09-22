package com.freezhub.integration;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates and summarises each channel's {@code config} (FZ-045).
 *
 * <p>{@code integration.config} is opaque to the rest of the system on purpose
 * (03-data-model.md) — each channel needs different settings. That opacity only works if
 * *something* owns the meaning, which is this: the one place that knows what a Slack
 * config or a webhook config has to contain.
 *
 * <p>It also decides what may be shown back. A Slack webhook URL is a bearer credential:
 * anyone holding it can post into that channel. It is stored, never returned.
 */
public final class IntegrationConfigs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IntegrationConfigs() {
    }

    /** Rejects a config the owning channel could not act on. */
    public static void validate(IntegrationType type, String config) {
        JsonNode parsed = parse(config);

        switch (type) {
            case SLACK -> requireHttpsUrl(parsed, "webhookUrl", "a Slack incoming webhook URL");
            case WEBHOOK -> requireHttpsUrl(parsed, "url", "an HTTPS endpoint URL");
            case EMAIL -> requireRecipients(parsed);
        }
    }

    /**
     * What may safely be shown back to a client: enough to tell destinations apart, never
     * enough to reuse one.
     */
    public static String summarise(IntegrationType type, String config) {
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(config);
        } catch (JacksonException unreadable) {
            return "unreadable configuration";
        }

        return switch (type) {
            // Host only. The path segment of a Slack webhook URL *is* the secret.
            case SLACK -> hostOf(parsed.path("webhookUrl").asText(""), "Slack");
            case WEBHOOK -> hostOf(parsed.path("url").asText(""), "webhook");
            case EMAIL -> {
                int count = parsed.path("recipients").size();
                yield count == 1 ? "1 recipient" : count + " recipients";
            }
        };
    }

    private static String hostOf(String url, String fallback) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? fallback : host;
        } catch (IllegalArgumentException notAUrl) {
            return fallback;
        }
    }

    private static JsonNode parse(String config) {
        try {
            JsonNode parsed = MAPPER.readTree(config);
            if (parsed == null || !parsed.isObject()) {
                throw badRequest("Configuration must be a JSON object");
            }
            return parsed;
        } catch (JacksonException malformed) {
            throw badRequest("Configuration is not valid JSON");
        }
    }

    /**
     * Checks the <em>shape</em> of a URL, and deliberately nothing else (FZ-189).
     *
     * <p><b>This is not the egress boundary and must never be mistaken for it.</b>
     * {@code OI-23} settled that question in {@code FZ-125}: "the fix is egress, not
     * validation", because a webhook URL is attacker-chosen by design and "validating at
     * save and resolving at send is a gap a DNS name can be moved through". The boundary
     * lives in {@code OutboundAddressPolicy}, at connect time, where {@code FZ-126} put it
     * — every resolved address checked, no redirects followed.
     *
     * <p>So no name is resolved here. What is checked is what a person can get wrong while
     * typing, reported while they are still looking at the field rather than as a delivery
     * failure three minutes later in the notification history.
     */
    private static void requireHttpsUrl(JsonNode config, String field, String description) {
        String value = config.path(field).asText("").trim();
        if (value.isBlank()) {
            throw badRequest("Configuration requires \"" + field + "\": " + description);
        }

        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException notAUrl) {
            throw badRequest("\"" + field + "\" is not a valid URL");
        }

        // https only: these carry credentials and freeze announcements.
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw badRequest("\"" + field + "\" must be an https URL");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw badRequest("\"" + field + "\" has no host");
        }

        /*
         * Refused here as well as at connect time, because the two refusals are for
         * different reasons and only one of them is security.
         *
         * `https://hooks.slack.com@10.0.0.5/` is a valid URL whose host is 10.0.0.5, and
         * OI-23 lists it as the trick that makes a `startsWith("https://")` check useless.
         * OutboundAddressPolicy already refuses it on the way out. Refusing it at the field
         * costs nothing and means an operator who pasted something odd finds out now,
         * rather than watching deliveries fail with a message they cannot act on.
         */
        if (uri.getUserInfo() != null) {
            throw badRequest("\"" + field + "\" must not contain a username before the host; "
                    + "everything before the @ is ignored by the server it reaches");
        }
    }

    private static void requireRecipients(JsonNode config) {
        JsonNode recipients = config.path("recipients");
        if (!recipients.isArray() || recipients.isEmpty()) {
            throw badRequest("Configuration requires \"recipients\": a non-empty list of email addresses");
        }
        for (JsonNode recipient : recipients) {
            String address = recipient.asText("");
            if (address.isBlank() || !address.contains("@")) {
                throw badRequest("\"" + address + "\" is not a valid email address");
            }
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

}
