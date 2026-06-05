package com.acme.sre.api;

import jakarta.ws.rs.core.Response;

/** Base for API-layer failures that map to a specific HTTP status and a typed error body. */
public abstract class ApiException extends RuntimeException {

    private final Response.Status status;

    protected ApiException(Response.Status status, String message) {
        super(message);
        this.status = status;
    }

    public Response.Status status() {
        return status;
    }
}
