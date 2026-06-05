package com.acme.sre.domain.contract;

/**
 * Typed outcome of a remediation that the on-call SRE rejected during Human-in-the-Loop approval.
 */
public record RemediationRejection(
        String status,
        String reviewer,
        String reason) {

    public static RemediationRejection from(ApprovalResponse approval) {
        return new RemediationRejection("REJECTED", approval.reviewer(), approval.reason());
    }
}
