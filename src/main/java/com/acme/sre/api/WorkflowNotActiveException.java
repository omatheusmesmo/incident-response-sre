package com.acme.sre.api;

import jakarta.ws.rs.core.Response;

public class WorkflowNotActiveException extends ApiException {

    public WorkflowNotActiveException(String incidentId) {
        super(Response.Status.BAD_REQUEST, "No active workflow for incident: " + incidentId);
    }
}
