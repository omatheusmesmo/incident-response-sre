package com.acme.sre.api;

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

import com.acme.sre.ai.commander.IncidentCommander;
import com.acme.sre.ai.triage.IncidentAnalyzer;
import com.acme.sre.ai.diagnostics.DiagnosticLoopAgent;
import com.acme.sre.ai.diagnostics.EvidenceGatherer;
import com.acme.sre.ai.diagnostics.IncidentDiagnosisService;
import com.acme.sre.ai.diagnostics.SeverityClassifier;
import com.acme.sre.ai.diagnostics.SeverityRouter;
import com.acme.sre.domain.Alert;
import com.acme.sre.domain.AlertInput;
import com.acme.sre.domain.ApprovalResponse;
import com.acme.sre.domain.Evidence;
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
    SeverityClassifier severityClassifier;

    @Inject
    EvidenceGatherer evidenceGatherer;

    @Inject
    SeverityRouter severityRouter;

    @Inject
    DiagnosticLoopAgent diagnosticLoopAgent;

    @Inject
    IncidentCommander incidentCommander;

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
    @Path("/parallel")
    public Response gatherEvidence(AlertInput alertInput) {
        String incidentId = createIncident(alertInput);
        String value = alertInput.value() != null ? alertInput.value().toString() : "N/A";

        Evidence evidence = evidenceGatherer.gather(incidentId,
                alertInput.service(), alertInput.message(), alertInput.metric(), value);

        return Response.ok(Map.of(
                "layer", "L2_PARALLEL",
                "pattern", "@ParallelAgent (fan-out: logs + metrics + deploy history)",
                "incidentId", incidentId,
                "evidence", evidence)).build();
    }

    @POST
    @Path("/conditional")
    public Response conditionalDiagnosis(AlertInput alertInput) {
        String incidentId = createIncident(alertInput);
        String value = alertInput.value() != null ? alertInput.value().toString() : "N/A";

        Severity severity = severityClassifier.classify(
                alertInput.message(), alertInput.service(), alertInput.metric(), value);
        Evidence evidence = evidenceGatherer.gather(incidentId,
                alertInput.service(), alertInput.message(), alertInput.metric(), value);

        var routed = severityRouter.route(incidentId,
                alertInput.service(), alertInput.message(), alertInput.metric(), value,
                severity, evidence.toPromptText());

        IncidentResult incidentResult = IncidentDiagnosisService.fromScope(routed.agenticScope(), severity);
        updateIncidentFromResult(incidentId, incidentResult);

        return Response.ok(Map.of(
                "layer", "L2_CONDITIONAL",
                "pattern", "@ConditionalAgent (severity router)",
                "branch", isDeep(severity) ? "deep" : "light",
                "incidentId", incidentId,
                "result", incidentResult)).build();
    }

    @POST
    @Path("/loop")
    public Response loopDiagnosis(AlertInput alertInput) {
        String incidentId = createIncident(alertInput);
        String value = alertInput.value() != null ? alertInput.value().toString() : "N/A";

        Severity severity = severityClassifier.classify(
                alertInput.message(), alertInput.service(), alertInput.metric(), value);
        Evidence evidence = evidenceGatherer.gather(incidentId,
                alertInput.service(), alertInput.message(), alertInput.metric(), value);

        var looped = diagnosticLoopAgent.diagnoseWithLoop(incidentId,
                alertInput.service(), alertInput.message(), severity, evidence.toPromptText());

        IncidentResult incidentResult = IncidentDiagnosisService.fromScope(looped.agenticScope(), severity);
        updateIncidentFromResult(incidentId, incidentResult);

        return Response.ok(Map.of(
                "layer", "L2_LOOP",
                "pattern", "@LoopAgent (diagnose -> remediate -> score, refine until confidence >= 0.8)",
                "incidentId", incidentId,
                "result", incidentResult)).build();
    }

    private static boolean isDeep(Severity severity) {
        return severity == Severity.P1_CRITICAL || severity == Severity.P2_HIGH;
    }

    @POST
    @Path("/commander")
    public Response commander(AlertInput alertInput) {
        String incidentId = createIncident(alertInput);
        String value = alertInput.value() != null ? alertInput.value().toString() : "N/A";
        String incidentText = "Service: %s, Alert: %s, Metric: %s=%s".formatted(
                alertInput.service(), alertInput.message(), alertInput.metric(), value);

        String assessment = incidentCommander.command(incidentText);

        return Response.ok(Map.of(
                "layer", "L2_SUPERVISOR",
                "pattern", "@SupervisorAgent (LLM-planned routing across domain specialists)",
                "incidentId", incidentId,
                "assessment", assessment)).build();
    }

    @POST
    @Path("/workflow")
    public Response startWorkflow(AlertInput alertInput) {
        String incidentId = createIncident(alertInput);
        String value = alertInput.value() != null ? alertInput.value().toString() : "N/A";

        TriagePrompt workflowInput = new TriagePrompt(
                alertInput.message(), alertInput.service(), alertInput.metric(), value, incidentId);

        WorkflowInstance instance = layer3Flow.instance(workflowInput);
        linkWorkflowInstance(incidentId, instance.id());

        instance.start();

        return Response.accepted(Map.of(
                "layer", "L3_FLOW",
                "incidentId", incidentId,
                "workflowInstanceId", instance.id(),
                "status", "STARTED")).build();
    }

    @Transactional
    void linkWorkflowInstance(String incidentId, String workflowInstanceId) {
        Incident incident = Incident.findById(incidentId);
        if (incident != null) {
            incident.workflowInstanceId = workflowInstanceId;
            incident.persist();
        }
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

    @PUT
    @Path("/workflow/{workflowInstanceId}/approve")
    public Response approveByWorkflow(@PathParam("workflowInstanceId") String workflowInstanceId,
            ApprovalResponse response) {
        ApprovalResponse approval = new ApprovalResponse(
                response != null ? response.incidentId() : null, true,
                response != null ? response.reviewer() : "unknown",
                response != null ? response.reason() : null);
        sendApprovalCloudEvent(workflowInstanceId, approval);
        return Response.ok(Map.of(
                "workflowInstanceId", workflowInstanceId,
                "action", "APPROVED")).build();
    }

    @PUT
    @Path("/workflow/{workflowInstanceId}/reject")
    public Response rejectByWorkflow(@PathParam("workflowInstanceId") String workflowInstanceId,
            ApprovalResponse response) {
        ApprovalResponse rejection = new ApprovalResponse(
                response != null ? response.incidentId() : null, false,
                response != null ? response.reviewer() : "unknown",
                response != null ? response.reason() : null);
        sendApprovalCloudEvent(workflowInstanceId, rejection);
        return Response.ok(Map.of(
                "workflowInstanceId", workflowInstanceId,
                "action", "REJECTED")).build();
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
