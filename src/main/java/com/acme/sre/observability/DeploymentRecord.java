package com.acme.sre.observability;

/** A deployment or configuration change (type {@code DEPLOY} or {@code FEATURE_FLAG}). */
public record DeploymentRecord(
        String service,
        String version,
        String deployedAt,
        String author,
        String type,
        String change) {

    public String toPromptText() {
        return "%s %s %s by %s: %s".formatted(deployedAt, type, version, author, change);
    }
}
