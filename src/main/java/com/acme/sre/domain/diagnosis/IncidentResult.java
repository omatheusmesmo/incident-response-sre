package com.acme.sre.domain.diagnosis;

import com.acme.sre.domain.model.Severity;

public record IncidentResult(
        Severity severity,
        Diagnosis diagnosis,
        RemediationAction remediation,
        ConfidenceScore confidenceScore,
        String postMortemSummary,
        MetricsSnapshot liveMetrics) {

    public boolean isRemediationDestructive() {
        return remediation != null && remediation.destructive();
    }

    public IncidentResult withLiveMetrics(MetricsSnapshot metrics) {
        return new IncidentResult(severity, diagnosis, remediation, confidenceScore, postMortemSummary, metrics);
    }

    public IncidentResult withPostMortem(String summary) {
        return new IncidentResult(severity, diagnosis, remediation, confidenceScore, summary, liveMetrics);
    }

    public String toPromptText() {
        return String.format(
                "Severity: %s. Diagnosis: %s. Remediation: %s. Confidence: %s. Live metrics: %s. Post-mortem: %s",
                severity, diagnosis != null ? diagnosis.toPromptText() : "N/A",
                remediation != null ? remediation.toPromptText() : "N/A",
                confidenceScore, liveMetrics != null ? liveMetrics.toPromptText() : "N/A", postMortemSummary);
    }
}
