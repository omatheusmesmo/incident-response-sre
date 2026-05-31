package com.acme.sre.domain;

public record IncidentResult(
        Severity severity,
        Diagnosis diagnosis,
        RemediationAction remediation,
        ConfidenceScore confidenceScore,
        String postMortemSummary) {

    public boolean isRemediationDestructive() {
        return remediation != null && remediation.destructive();
    }

    public String toPromptText() {
        return String.format(
                "Severity: %s. Diagnosis: %s. Remediation: %s. Confidence: %s. Post-mortem: %s",
                severity, diagnosis != null ? diagnosis.toPromptText() : "N/A",
                remediation != null ? remediation.toPromptText() : "N/A",
                confidenceScore, postMortemSummary);
    }
}
