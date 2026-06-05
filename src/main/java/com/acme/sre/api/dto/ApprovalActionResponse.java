package com.acme.sre.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Layer 3 HITL response: the outcome of an approve/reject decision. {@code incidentId} is omitted
 * when the decision was made by workflow instance id (decoupled from the entity).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalActionResponse(
        String incidentId,
        String workflowInstanceId,
        String action) {

    public static ApprovalActionResponse approved(String incidentId, String workflowInstanceId) {
        return new ApprovalActionResponse(incidentId, workflowInstanceId, "APPROVED");
    }

    public static ApprovalActionResponse rejected(String incidentId, String workflowInstanceId) {
        return new ApprovalActionResponse(incidentId, workflowInstanceId, "REJECTED");
    }
}
