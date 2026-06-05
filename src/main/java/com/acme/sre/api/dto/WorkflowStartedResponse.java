package com.acme.sre.api.dto;

/** Layer 3 response: acknowledgement that the durable workflow instance has been started. */
public record WorkflowStartedResponse(
        String layer,
        String incidentId,
        String workflowInstanceId,
        String status) {

    public static WorkflowStartedResponse started(String incidentId, String workflowInstanceId) {
        return new WorkflowStartedResponse("L3_FLOW", incidentId, workflowInstanceId, "STARTED");
    }
}
