package com.acme.sre.domain;

public record IncidentAnalysis(
        Severity severity,
        String probableCause,
        String suggestedAction) {

    public String toPromptText() {
        return String.format("Severity: %s. Probable cause: %s. Suggested action: %s",
                severity.toPromptLabel(), probableCause, suggestedAction);
    }
}
