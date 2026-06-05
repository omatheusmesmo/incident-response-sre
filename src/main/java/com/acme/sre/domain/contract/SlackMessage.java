package com.acme.sre.domain.contract;

/**
 * Typed request body for the Slack incoming-webhook integration.
 */
public record SlackMessage(
        String channel,
        String message) {
}
