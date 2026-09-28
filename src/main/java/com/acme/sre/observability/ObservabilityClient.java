package com.acme.sre.observability;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import com.acme.sre.domain.diagnosis.MetricsSnapshot;

/**
 * Read-only view of the observability stack (Prometheus, log aggregation, deploy history).
 * In dev and test the base URL points at the WireMock Dev Service, which serves one coherent
 * story per service from {@code src/test/resources/mappings/world-*.json}.
 */
@RegisterRestClient(configKey = "observability-api")
public interface ObservabilityClient {

    @GET
    @Path("/prometheus/query")
    MetricsSnapshot metrics(@QueryParam("service") String service);

    @GET
    @Path("/logs")
    List<LogEntry> logs(@QueryParam("service") String service);

    @GET
    @Path("/deployments")
    List<DeploymentRecord> deployments(@QueryParam("service") String service);
}
