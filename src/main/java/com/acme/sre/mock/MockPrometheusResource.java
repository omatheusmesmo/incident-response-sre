package com.acme.sre.mock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import com.acme.sre.domain.MetricsData;

@Path("/mock/prometheus")
public class MockPrometheusResource {

    @GET
    @Path("/query")
    public Response queryMetrics(@QueryParam("query") String query) {
        Map<String, Object> response = Map.of(
                "status", "success",
                "data", Map.of(
                        "resultType", "vector",
                        "result", List.of(
                                Map.of(
                                        "metric", Map.of(
                                                "__name__", query != null ? query : "cpu_usage",
                                                "service", "api-gateway",
                                                "instance", "10.0.1.5:8080"),
                                        "value", List.of(
                                                Instant.now().getEpochSecond(),
                                                "0.95")))));

        return Response.ok(response).build();
    }

    @GET
    @Path("/metrics/{service}")
    public MetricsData getServiceMetrics(@QueryParam("service") String service) {
        return new MetricsData(
                service != null ? service : "api-gateway",
                95.0,
                89.0,
                12.0,
                450.0,
                "cpu_usage{service=\"" + service + "\"}");
    }
}
