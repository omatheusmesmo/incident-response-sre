package com.acme.sre.api;

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
import com.acme.sre.api.dto.AlertAnalysisResponse;
import com.acme.sre.api.dto.ApprovalActionResponse;
import com.acme.sre.api.dto.CommanderResponse;
import com.acme.sre.api.dto.ConditionalDiagnosisResponse;
import com.acme.sre.api.dto.EvidenceResponse;
import com.acme.sre.api.dto.IncidentView;
import com.acme.sre.api.dto.LoopDiagnosisResponse;
import com.acme.sre.api.dto.WorkflowStartedResponse;
import com.acme.sre.domain.contract.AlertInput;
import com.acme.sre.domain.contract.ApprovalResponse;
import com.acme.sre.domain.contract.TriagePrompt;
import com.acme.sre.domain.diagnosis.Evidence;
import com.acme.sre.domain.diagnosis.IncidentAnalysis;
import com.acme.sre.domain.diagnosis.IncidentResult;
import com.acme.sre.domain.model.Alert;
import com.acme.sre.domain.model.Incident;
import com.acme.sre.domain.model.IncidentStatus;
import com.acme.sre.domain.model.Severity;
import com.acme.sre.flow.IncidentResponseFlow;
import com.acme.sre.messaging.ApprovalEventPublisher;

import io.serverlessworkflow.impl.WorkflowInstance;

/**
 * REST surface for the 3-layer incident-response demo. Each layer is reachable on its own
 * endpoint so the evolution is visible in isolation:
 * <ul>
 *   <li><b>Layer 1</b> - a single stateless {@code @RegisterAiService} (ChatModel).</li>
 *   <li><b>Layer 2</b> - the LangChain4j agentic patterns, one endpoint per pattern.</li>
 *   <li><b>Layer 3</b> - the durable, human-gated, event-driven {@link IncidentResponseFlow}.</li>
 * </ul>
 * Each endpoint returns a typed response DTO; every log line is tagged with a {@code [Ln:...]}
 * prefix and the incident id so a single incident can be traced across the layers.
 */
@Path("/incidents")
@ApplicationScoped
public class IncidentResource {

    private static final Logger LOG = Logger.getLogger(IncidentResource.class);

    /** Layer 1: a single stateless AI service that turns alert text into structured analysis. */
    @Inject
    IncidentAnalyzer incidentAnalyzer;

    /** Layer 2: agentic patterns, each exposed on its own endpoint. */
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

    /** Layer 3: the durable, human-gated, event-driven workflow that orchestrates the agentic work. */
    @Inject
    IncidentResponseFlow incidentResponseFlow;

    @Inject
    ApprovalEventPublisher approvalEventPublisher;

    /**
     * Layer 1 - ChatModel. A single stateless AI call that turns alert text into structured
     * analysis. No memory, no iteration, no durability.
     */
    @POST
    @Path("/alert")
    @Transactional
    public AlertAnalysisResponse analyzeAlert(AlertInput alertInput) {
        Alert alert = alertInput.toAlert();
        alert.persist();
        LOG.infof("[L1] alert received | alert=%s service=%s metric=%s", alert.id, alert.service, alert.metric);

        IncidentAnalysis analysis = incidentAnalyzer.analyze(alert.id, alert.toPromptText());

        Incident incident = Incident.fromAlert(alert);
        incident.severity = analysis.severity();
        incident.diagnosis = analysis.probableCause();
        incident.rootCause = analysis.probableCause();
        incident.remediationDescription = analysis.suggestedAction();
        incident.status = IncidentStatus.TRIAGED;
        incident.persist();

        LOG.infof("[L1] analysis done | incident=%s severity=%s", incident.id, analysis.severity());
        return AlertAnalysisResponse.of(incident.id, alert.id, analysis);
    }

    /**
     * Layer 2 - {@code @ParallelAgent}. Fan-out: logs, metrics and deploy-history evidence agents
     * run concurrently (on the small/fast model) and their findings are merged.
     */
    @POST
    @Path("/parallel")
    public EvidenceResponse gatherEvidence(AlertInput alertInput) {
        String incidentId = createIncident(alertInput);
        String value = valueOf(alertInput);
        LOG.infof("[L2:parallel] gathering evidence | incident=%s service=%s", incidentId, alertInput.service());

        Evidence evidence = evidenceGatherer.gather(incidentId,
                alertInput.service(), alertInput.message(), alertInput.metric(), value);

        LOG.infof("[L2:parallel] evidence gathered | incident=%s", incidentId);
        return EvidenceResponse.of(incidentId, evidence);
    }

    /**
     * Layer 2 - {@code @ConditionalAgent}. Severity router: P1/P2 take a deep, evidence-driven
     * diagnosis, P3/P4 a light triage, selected by an {@code @ActivationCondition}.
     */
    @POST
    @Path("/conditional")
    public ConditionalDiagnosisResponse conditionalDiagnosis(AlertInput alertInput) {
        String incidentId = createIncident(alertInput);
        String value = valueOf(alertInput);

        Severity severity = severityClassifier.classify(
                alertInput.message(), alertInput.service(), alertInput.metric(), value);
        Evidence evidence = evidenceGatherer.gather(incidentId,
                alertInput.service(), alertInput.message(), alertInput.metric(), value);

        String branch = isDeep(severity) ? "deep" : "light";
        LOG.infof("[L2:conditional] routing | incident=%s severity=%s branch=%s", incidentId, severity, branch);

        var routed = severityRouter.route(incidentId,
                alertInput.service(), alertInput.message(), alertInput.metric(), value,
                severity, evidence.toPromptText());

        IncidentResult incidentResult = IncidentDiagnosisService.fromScope(routed.agenticScope(), severity);
        updateIncidentFromResult(incidentId, incidentResult);

        LOG.infof("[L2:conditional] diagnosis done | incident=%s branch=%s severity=%s remediation=%s destructive=%s",
                incidentId, branch, incidentResult.severity(),
                incidentResult.remediation() != null ? incidentResult.remediation().type() : null,
                incidentResult.isRemediationDestructive());
        return ConditionalDiagnosisResponse.of(branch, incidentId, incidentResult);
    }

    /**
     * Layer 2 - {@code @LoopAgent}. Diagnose -> remediate -> score, refined until the
     * {@code @ExitCondition} confidence reaches 0.8. Carries an {@code @ErrorHandler} for resilience.
     */
    @POST
    @Path("/loop")
    public LoopDiagnosisResponse loopDiagnosis(AlertInput alertInput) {
        String incidentId = createIncident(alertInput);
        String value = valueOf(alertInput);

        Severity severity = severityClassifier.classify(
                alertInput.message(), alertInput.service(), alertInput.metric(), value);
        Evidence evidence = evidenceGatherer.gather(incidentId,
                alertInput.service(), alertInput.message(), alertInput.metric(), value);
        LOG.infof("[L2:loop] refining diagnosis | incident=%s severity=%s", incidentId, severity);

        var looped = diagnosticLoopAgent.diagnoseWithLoop(incidentId,
                alertInput.service(), alertInput.message(), severity, evidence.toPromptText());

        IncidentResult incidentResult = IncidentDiagnosisService.fromScope(looped.agenticScope(), severity);
        updateIncidentFromResult(incidentId, incidentResult);

        double confidence = incidentResult.confidenceScore() != null ? incidentResult.confidenceScore().value() : 0.0;
        LOG.infof("[L2:loop] diagnosis done | incident=%s severity=%s remediation=%s destructive=%s confidence=%.2f",
                incidentId, incidentResult.severity(),
                incidentResult.remediation() != null ? incidentResult.remediation().type() : null,
                incidentResult.isRemediationDestructive(), confidence);
        return LoopDiagnosisResponse.of(incidentId, incidentResult);
    }

    private static boolean isDeep(Severity severity) {
        return severity == Severity.P1_CRITICAL || severity == Severity.P2_HIGH;
    }

    /**
     * Layer 2 - {@code @SupervisorAgent}. An LLM "incident commander" autonomously decides which
     * domain specialists (database, kubernetes, network, cache) to consult, then synthesizes their
     * findings ({@code responseStrategy = SUMMARY}).
     */
    @POST
    @Path("/commander")
    public CommanderResponse commander(AlertInput alertInput) {
        String incidentId = createIncident(alertInput);
        String incidentText = "Service: %s, Alert: %s, Metric: %s=%s".formatted(
                alertInput.service(), alertInput.message(), alertInput.metric(), valueOf(alertInput));
        LOG.infof("[L2:supervisor] commander assessing | incident=%s service=%s", incidentId, alertInput.service());

        String assessment = incidentCommander.command(incidentText);

        LOG.infof("[L2:supervisor] assessment done | incident=%s", incidentId);
        return CommanderResponse.of(incidentId, assessment);
    }

    /**
     * Layer 3 - durable Flow. Starts the {@link IncidentResponseFlow}: agentic diagnosis, live
     * metrics enrichment, an approval gate for destructive remediation (HITL), remediation
     * execution, Slack notification and an agentic post-mortem. Returns immediately with the
     * workflow instance id; progress is observed via events / the live console.
     */
    @POST
    @Path("/workflow")
    public Response startWorkflow(AlertInput alertInput) {
        String incidentId = createIncident(alertInput);

        TriagePrompt workflowInput = new TriagePrompt(
                alertInput.message(), alertInput.service(), alertInput.metric(), valueOf(alertInput), incidentId);

        WorkflowInstance instance = incidentResponseFlow.instance(workflowInput);
        linkWorkflowInstance(incidentId, instance.id());
        instance.start();

        LOG.infof("[L3] workflow started | incident=%s instance=%s service=%s",
                incidentId, instance.id(), alertInput.service());
        return Response.accepted(WorkflowStartedResponse.started(incidentId, instance.id())).build();
    }

    @Transactional
    void linkWorkflowInstance(String incidentId, String workflowInstanceId) {
        Incident incident = Incident.findById(incidentId);
        if (incident != null) {
            incident.workflowInstanceId = workflowInstanceId;
            incident.persist();
        }
    }

    /**
     * Layer 3 HITL - approve a destructive remediation by incident id. Emits the approval
     * CloudEvent that resumes the workflow paused at its {@code listen} gate.
     */
    @PUT
    @Path("/{incidentId}/approve")
    @Transactional
    public ApprovalActionResponse approveRemediation(@PathParam("incidentId") String incidentId,
            ApprovalResponse response) {
        Incident incident = requireLinkedIncident(incidentId);

        ApprovalResponse approval = new ApprovalResponse(incidentId, true, response.reviewer(), response.reason());
        approvalEventPublisher.publishDecision(incident.workflowInstanceId, approval);

        incident.status = IncidentStatus.REMEDIATING;
        incident.persist();

        LOG.infof("[L3:HITL] approved | incident=%s instance=%s reviewer=%s",
                incidentId, incident.workflowInstanceId, response.reviewer());
        return ApprovalActionResponse.approved(incidentId, incident.workflowInstanceId);
    }

    /**
     * Layer 3 HITL - reject a destructive remediation by incident id. Emits the rejection
     * CloudEvent that resumes the workflow down its rejection branch (notify, then END).
     */
    @PUT
    @Path("/{incidentId}/reject")
    @Transactional
    public ApprovalActionResponse rejectRemediation(@PathParam("incidentId") String incidentId,
            ApprovalResponse response) {
        Incident incident = requireLinkedIncident(incidentId);

        ApprovalResponse rejection = new ApprovalResponse(incidentId, false, response.reviewer(), response.reason());
        approvalEventPublisher.publishDecision(incident.workflowInstanceId, rejection);

        incident.status = IncidentStatus.ESCALATED;
        incident.persist();

        LOG.infof("[L3:HITL] rejected | incident=%s instance=%s reviewer=%s",
                incidentId, incident.workflowInstanceId, response.reviewer());
        return ApprovalActionResponse.rejected(incidentId, incident.workflowInstanceId);
    }

    /**
     * Layer 3 HITL - approve by <b>workflow instance id</b>, decoupled from the {@code Incident}
     * entity. Lets HITL still work after a restart even if the entity DB was reset.
     */
    @PUT
    @Path("/workflow/{workflowInstanceId}/approve")
    public ApprovalActionResponse approveByWorkflow(@PathParam("workflowInstanceId") String workflowInstanceId,
            ApprovalResponse response) {
        ApprovalResponse approval = decisionFor(response, true);
        approvalEventPublisher.publishDecision(workflowInstanceId, approval);
        LOG.infof("[L3:HITL] approved by instance | instance=%s reviewer=%s", workflowInstanceId, approval.reviewer());
        return ApprovalActionResponse.approved(null, workflowInstanceId);
    }

    /**
     * Layer 3 HITL - reject by <b>workflow instance id</b>, decoupled from the {@code Incident}
     * entity. Lets HITL still work after a restart even if the entity DB was reset.
     */
    @PUT
    @Path("/workflow/{workflowInstanceId}/reject")
    public ApprovalActionResponse rejectByWorkflow(@PathParam("workflowInstanceId") String workflowInstanceId,
            ApprovalResponse response) {
        ApprovalResponse rejection = decisionFor(response, false);
        approvalEventPublisher.publishDecision(workflowInstanceId, rejection);
        LOG.infof("[L3:HITL] rejected by instance | instance=%s reviewer=%s", workflowInstanceId, rejection.reviewer());
        return ApprovalActionResponse.rejected(null, workflowInstanceId);
    }

    @GET
    @Path("/{incidentId}")
    @Transactional
    public IncidentView getIncident(@PathParam("incidentId") String incidentId) {
        Incident incident = Incident.findById(incidentId);
        if (incident == null) {
            throw new IncidentNotFoundException(incidentId);
        }
        return IncidentView.from(incident);
    }

    private Incident requireLinkedIncident(String incidentId) {
        Incident incident = Incident.findById(incidentId);
        if (incident == null) {
            throw new IncidentNotFoundException(incidentId);
        }
        if (incident.workflowInstanceId == null) {
            throw new WorkflowNotActiveException(incidentId);
        }
        return incident;
    }

    private static ApprovalResponse decisionFor(ApprovalResponse response, boolean approved) {
        return new ApprovalResponse(
                response != null ? response.incidentId() : null,
                approved,
                response != null ? response.reviewer() : "unknown",
                response != null ? response.reason() : null);
    }

    private static String valueOf(AlertInput alertInput) {
        return alertInput.value() != null ? alertInput.value().toString() : "N/A";
    }

    @Transactional
    String createIncident(AlertInput alertInput) {
        Alert alert = alertInput.toAlert();
        alert.persist();

        Incident incident = Incident.fromAlert(alert);
        incident.status = IncidentStatus.DIAGNOSING;
        incident.persist();

        LOG.infof("incident created | incident=%s service=%s metric=%s", incident.id, alert.service, alert.metric);
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
}
