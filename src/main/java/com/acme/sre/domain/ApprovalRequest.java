package com.acme.sre.domain;

public record ApprovalRequest(
        String incidentId,
        String workflowInstanceId,
        RemediationAction remediation,
        String diagnosis) {
}
