package com.acme.sre.domain;

public record ApprovalResponse(
        String incidentId,
        boolean approved,
        String reviewer,
        String reason) {
}
