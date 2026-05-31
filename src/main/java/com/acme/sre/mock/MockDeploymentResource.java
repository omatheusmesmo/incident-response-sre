package com.acme.sre.mock;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

@Path("/mock/deployment")
public class MockDeploymentResource {

    private static final Logger LOG = Logger.getLogger(MockDeploymentResource.class);
    private final Map<String, RemediationRecord> remediationLog = new ConcurrentHashMap<>();

    @POST
    @Path("/remediate")
    public Response executeRemediation(Map<String, Object> action) {
        String actionId = UUID.randomUUID().toString();
        String actionType = (String) action.getOrDefault("type", "UNKNOWN");
        String targetService = (String) action.getOrDefault("targetService", "unknown");
        String description = (String) action.getOrDefault("description", "no description");

        LOG.infof("Mock deployment API: executing remediation %s on %s - %s", actionType, targetService, description);

        remediationLog.put(actionId, new RemediationRecord(actionId, actionType, targetService, Instant.now(), "COMPLETED"));

        return Response.ok(Map.of(
                "actionId", actionId,
                "status", "COMPLETED",
                "message", String.format("Remediation '%s' executed on '%s' successfully", actionType, targetService),
                "timestamp", Instant.now().toString())).build();
    }

    public Map<String, RemediationRecord> getRemediationLog() {
        return Map.copyOf(remediationLog);
    }

    public record RemediationRecord(String actionId, String actionType, String targetService, Instant executedAt, String status) {
    }
}
