package com.acme.sre.domain.contract;

/**
 * Typed request body sent to the deployment controller to execute a remediation.
 */
public record RemediationCommand(
        String description,
        String targetService,
        String action) {
}
