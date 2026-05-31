package com.acme.sre.domain;

public record Diagnosis(
        String rootCause,
        String explanation,
        String affectedComponent) {

    public String toPromptText() {
        return String.format("Root cause: %s. Explanation: %s. Affected: %s",
                rootCause, explanation, affectedComponent);
    }
}
