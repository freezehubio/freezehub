package com.freezhub.demo;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Announces a demo request in FreezeHub's own Slack (`FZ-083`, extracted by `FZ-214`).
 *
 * <p>Unchanged in behaviour from the class it came out of. It is a {@code @Component} rather
 * than conditional on the webhook property because {@link #isConfigured()} already answers
 * that question, and the notifier asks every channel: a bean that exists and says "not
 * configured" is easier to reason about than one that is absent from the list.
 */
@Component
public class SlackDemoRequestChannel implements DemoRequestChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final String webhookUrl;

    public SlackDemoRequestChannel(RestClient.Builder restClientBuilder,
                                   @Value("${freezehub.demo-requests.slack-webhook:}") String webhookUrl) {
        this.restClient = restClientBuilder.build();
        this.webhookUrl = webhookUrl;
    }

    @Override
    public boolean isConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    @Override
    public String name() {
        return "Slack";
    }

    @Override
    public void announce(DemoRequest request) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("text", messageFor(request));

        try {
            restClient.post()
                    .uri(webhookUrl)
                    .header("Content-Type", "application/json")
                    .body(payload.toString())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException failed) {
            // The class name only. Spring puts the request URI in the message, and that URI
            // is the webhook — a bearer credential that would then be written to
            // `notify_error` and read by whoever queries the table.
            throw new DemoRequestChannelException(
                    "rejected or could not be reached: " + failed.getClass().getSimpleName());
        }
    }

    /**
     * Built with Jackson rather than by concatenation.
     *
     * <p>A company called {@code O"Brien "Ltd"} is not hypothetical, and the same mistake in
     * the audit trail produced an unparseable row once already ({@code FZ-060}).
     */
    private String messageFor(DemoRequest request) {
        StringBuilder text = new StringBuilder("*Demo requested* — ")
                .append(request.getCompany())
                .append("\n")
                .append(request.getName())
                .append(" · ")
                .append(request.getEmail());
        if (request.getTeamSize() != null && !request.getTeamSize().isBlank()) {
            text.append(" · ").append(request.getTeamSize()).append(" engineers");
        }
        if (request.getSource() != null && !request.getSource().isBlank()) {
            text.append("\nFrom: ").append(request.getSource());
        }
        if (request.getMessage() != null && !request.getMessage().isBlank()) {
            text.append("\n> ").append(request.getMessage());
        }
        return text.toString();
    }
}
