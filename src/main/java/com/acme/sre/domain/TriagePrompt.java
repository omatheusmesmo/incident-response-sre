package com.acme.sre.domain;

public record TriagePrompt(
        String message,
        String service,
        String metric,
        String value,
        String incidentId) {

    public String toPromptText() {
        return String.format(
                "Alert - Service: %s, Metric: %s, Value: %s. Message: %s",
                service, metric, value, message);
    }
}
