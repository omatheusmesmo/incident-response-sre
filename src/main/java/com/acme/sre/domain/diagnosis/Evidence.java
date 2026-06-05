package com.acme.sre.domain.diagnosis;

public record Evidence(
        String logsFindings,
        String metricsFindings,
        String deployFindings) {

    public String toPromptText() {
        return String.format(
                "Logs: %s | Metrics: %s | Deploy history: %s",
                logsFindings, metricsFindings, deployFindings);
    }
}
