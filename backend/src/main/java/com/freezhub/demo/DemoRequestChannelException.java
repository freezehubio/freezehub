package com.freezhub.demo;

/**
 * A channel could not deliver (`FZ-214`).
 *
 * <p><strong>The message is written to {@code demo_request.notify_error}</strong>, so it
 * must never carry a credential. A Slack webhook URL <em>is</em> the credential, and Spring
 * puts the request URI in {@code RestClientException}'s message — which is why the causes
 * are reduced to a class name before they reach here rather than being wrapped whole.
 */
public class DemoRequestChannelException extends RuntimeException {

    public DemoRequestChannelException(String message) {
        super(message);
    }
}
