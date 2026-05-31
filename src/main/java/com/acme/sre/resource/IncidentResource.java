package com.acme.sre.resource;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import com.acme.sre.ai.IncidentAnalyzer;
import com.acme.sre.ai.IncidentResponseAgent;
import com.acme.sre.domain.Alert;
import com.acme.sre.domain.AlertInput;
import com.acme.sre.domain.ApprovalResponse;
import com.acme.sre.domain.ConfidenceScore;
import com.acme.sre.domain.Diagnosis;
import com.acme.sre.domain.Incident;
import com.acme.sre.domain.IncidentAnalysis;
import com.acme.sre.domain.IncidentResult;
import com.acme.sre.domain.IncidentStatus;
import com.acme.sre.domain.Severity;
import com.acme.sre.domain.TriagePrompt;
import com.acme.sre.flow.IncidentResponseFlow;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
import io.serverlessworkflow.impl.WorkflowInstance;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

@Path("/incidents")
@ApplicationScoped
public class IncidentResource {

    private static final Logger LOG = Logger.getLogger(IncidentResource.class);

    @Inject
    IncidentAnalyzer layer1Analyzer;

    @Inject
    IncidentResponseAgent layer2Agent;

    @Inject
    IncidentResponseFlow layer3Flow;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    @Channel("flow-in-outgoing")
    Emitter<byte[]> flowInEmitter;

    @POST
    @Path("/alert")
    @Transactional
    public Response analyzeAlert(AlertInput alertInput) {
        Alert alert = alertInput.toAlert();
        alert.persist();

        IncidentAnalysis analysis = layer1Analyzer.analyze(alert.id, alert.toPromptText());

        Incident incident = Incident.fromAlert(alert);
        incident.severity = analysis.severity();
        incident.diagnosis = analysis.probableCause();
        incident.rootCause = analysis.probableCause();
        incident.remediationDescription = analysis.suggestedAction();
        incident.status = IncidentStatus.TRIAGED;
        incident.persist();

        return Response.ok(Map.of(
                "layer", "L1_CHATMODEL",
                "incidentId", incident.id,
                "alertId", alert.id,
                "analysis", analysis)).build();
    }

    @POST
    @Path("/analyze-agentic")
    public Response analyzeAgentic(AlertInput alertInput) {
        String incidentId = createIncident(alertInput);

        IncidentResult incidentResult;
        try {
            var result = layer2Agent.respond(
                incidentId,
                alertInput.message(),
                alertInput.service(),
                alertInput.metric(),
                alertInput.value() != null ? alertInput.value().toString() : "N/A");
            incidentResult = result.result();
        } catch (Exception e) {
            incidentResult = new IncidentResult(
                Severity.P1_CRITICAL, new Diagnosis("unknown", e.getMessage(), "unknown"),
                null, new ConfidenceScore(0.0, "Agent failed: " + e.getMessage()), null);

        }
        updateIncidentFromResult(incidentId, incidentResult);

        return Response.ok(Map.of(
            "layer", "L2_AGENTIC",
            "incidentId", incidentId,
            "result", incidentResult)).build();
    }

    @POST
    @Path("/workflow")
    @Transactional
    public Response startWorkflow(AlertInput alertInput) {
        Alert alert = alertInput.toAlert();
        alert.persist();

        Incident incident = Incident.fromAlert(alert);
        incident.status = IncidentStatus.DIAGNOSING;
        incident.persist();

        TriagePrompt workflowInput = new TriagePrompt(
                alert.message,
                alert.service,
                alert.metric,
                alert.value != null ? alert.value.toString() : "N/A",
                incident.id);

        WorkflowInstance instance = layer3Flow.instance(workflowInput);
        incident.workflowInstanceId = instance.id();
        incident.persist();

        instance.start();

        return Response.accepted(Map.of(
                "layer", "L3_FLOW",
                "incidentId", incident.id,
                "alertId", alert.id,
                "workflowInstanceId", instance.id(),
                "status", "STARTED")).build();
    }

    @PUT
    @Path("/{incidentId}/approve")
    @Transactional
    public Response approveRemediation(@PathParam("incidentId") String incidentId,
            ApprovalResponse response) {
        Incident incident = Incident.findById(incidentId);
        if (incident == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Incident not found: " + incidentId))
                    .build();
        }
        if (incident.workflowInstanceId == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "No active workflow for incident: " + incidentId))
                    .build();
        }

        ApprovalResponse approval = new ApprovalResponse(incidentId, true, response.reviewer(), response.reason());
        sendApprovalCloudEvent(incident.workflowInstanceId, approval);

        incident.status = IncidentStatus.REMEDIATING;
        incident.persist();

        return Response.ok(Map.of(
                "incidentId", incidentId,
                "action", "APPROVED",
                "workflowInstanceId", incident.workflowInstanceId)).build();
    }

    @PUT
    @Path("/{incidentId}/reject")
    @Transactional
    public Response rejectRemediation(@PathParam("incidentId") String incidentId,
            ApprovalResponse response) {
        Incident incident = Incident.findById(incidentId);
        if (incident == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Incident not found: " + incidentId))
                    .build();
        }
        if (incident.workflowInstanceId == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "No active workflow for incident: " + incidentId))
                    .build();
        }

        ApprovalResponse rejection = new ApprovalResponse(incidentId, false, response.reviewer(), response.reason());
        sendApprovalCloudEvent(incident.workflowInstanceId, rejection);

        incident.status = IncidentStatus.ESCALATED;
        incident.persist();

        return Response.ok(Map.of(
                "incidentId", incidentId,
                "action", "REJECTED",
                "workflowInstanceId", incident.workflowInstanceId)).build();
    }

    @GET
    @Path("/{incidentId}")
    @Transactional
    public Response getIncident(@PathParam("incidentId") String incidentId) {
        Incident incident = Incident.findById(incidentId);
        if (incident == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Incident not found: " + incidentId))
                    .build();
        }
        return Response.ok(incident).build();
    }

    @Transactional
    String createIncident(AlertInput alertInput) {
        Alert alert = alertInput.toAlert();
        alert.persist();

        Incident incident = Incident.fromAlert(alert);
        incident.status = IncidentStatus.DIAGNOSING;
        incident.persist();

        return incident.id;
    }

    @Transactional
    void updateIncidentFromResult(String incidentId, IncidentResult result) {
        Incident incident = Incident.findById(incidentId);
        if (incident == null) {
            return;
        }
        incident.severity = result.severity();
        incident.diagnosis = result.diagnosis() != null ? result.diagnosis().explanation() : null;
        incident.rootCause = result.diagnosis() != null ? result.diagnosis().rootCause() : null;
        incident.confidenceScore = result.confidenceScore() != null ? result.confidenceScore().value() : 0.0;
        incident.postMortemSummary = result.postMortemSummary();
        incident.remediationDescription = result.remediation() != null ? result.remediation().description() : null;
        incident.remediationDestructive = result.remediation() != null && result.remediation().destructive();
        incident.remediationType = result.remediation() != null ? result.remediation().type() : null;
        incident.remediationTargetService = result.remediation() != null ? result.remediation().targetService() : null;
        incident.status = IncidentStatus.RESOLVED;
        incident.persist();
    }

    private void sendApprovalCloudEvent(String workflowInstanceId, ApprovalResponse approval) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(approval);
            CloudEvent ce = CloudEventBuilder.v1()
                    .withId(UUID.randomUUID().toString())
                    .withExtension("flowinstanceid", workflowInstanceId)
                    .withSource(URI.create("api:/incidents"))
                    .withType("com.acme.sre.incident.approval.done")
                    .withDataContentType("application/json")
                    .withData(body)
                    .build();

            byte[] ceBytes = new JsonFormat().serialize(ce);
            flowInEmitter.send(ceBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send approval CloudEvent", e);
        }
    }
}
