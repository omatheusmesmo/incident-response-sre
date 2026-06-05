package com.acme.sre.api;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import org.jboss.logging.Logger;

import com.acme.sre.api.dto.ApiError;

/** Renders {@link ApiException}s as a typed {@link ApiError} body with the intended HTTP status. */
@Provider
public class ApiExceptionMapper implements ExceptionMapper<ApiException> {

    private static final Logger LOG = Logger.getLogger(ApiExceptionMapper.class);

    @Override
    public Response toResponse(ApiException exception) {
        LOG.debugf("API error %s: %s", exception.status().getStatusCode(), exception.getMessage());
        return Response.status(exception.status())
                .entity(new ApiError(exception.getMessage()))
                .build();
    }
}
