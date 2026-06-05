package com.acme.sre.domain.contract;

/**
 * Typed response returned by the Slack incoming-webhook integration.
 */
public record SlackAck(
        boolean ok,
        String channel,
        String ts) {
}
