package com.acme.sre.api;

import jakarta.ws.rs.core.Response;

public class IncidentNotFoundException extends ApiException {

    public IncidentNotFoundException(String incidentId) {
        super(Response.Status.NOT_FOUND, "Incident not found: " + incidentId);
    }
}
