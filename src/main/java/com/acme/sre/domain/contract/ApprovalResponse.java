package com.acme.sre.domain.contract;

public record ApprovalResponse(
        String incidentId,
        boolean approved,
        String reviewer,
        String reason) {
}
