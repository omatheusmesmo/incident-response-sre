package com.acme.sre.ai.diagnostics;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.acme.sre.domain.diagnosis.ConfidenceScore;
import com.acme.sre.domain.diagnosis.Diagnosis;
import com.acme.sre.domain.diagnosis.Evidence;
import com.acme.sre.domain.diagnosis.IncidentResult;
import com.acme.sre.domain.diagnosis.RemediationAction;
import com.acme.sre.domain.model.Severity;
import com.acme.sre.domain.contract.TriagePrompt;

import dev.langchain4j.agentic.scope.AgenticScope;

/**
 * Plain-CDI orchestration of the Layer 2 agentic patterns into a single diagnosis:
 * classify severity, gather evidence in parallel, then route to deep or light diagnosis.
 * <p>
 * Each agentic call is a single one-level pattern: the {@code @ParallelAgent}
 * (evidence) and the {@code @ConditionalAgent} (router) are never composed in the
 * same AOT graph, which is what avoids the planner deadlock. The durable Layer 3
 * Flow invokes {@link #diagnose(TriagePrompt)} as a Pattern B subflow, adding
 * durability, Human-in-the-Loop approval and observability on top of the agents.
 */
@ApplicationScoped
public class IncidentDiagnosisService {

    private static final Logger LOG = Logger.getLogger(IncidentDiagnosisService.class);

    @Inject
    SeverityClassifier severityClassifier;

    @Inject
    EvidenceGatherer evidenceGatherer;

    @Inject
    SeverityRouter severityRouter;

    public IncidentResult diagnose(TriagePrompt alert) {
        String memoryId = alert.incidentId() != null ? alert.incidentId() : UUID.randomUUID().toString();
        LOG.infof("[L3:diagnose] agentic pipeline start | incident=%s service=%s metric=%s",
                memoryId, alert.service(), alert.metric());

        Severity severity = severityClassifier.classify(
                alert.message(), alert.service(), alert.metric(), alert.value());
        LOG.infof("[L3:diagnose] classified | incident=%s severity=%s path=%s",
                memoryId, severity, isDeep(severity) ? "deep" : "light");

        Evidence evidence = evidenceGatherer.gather(
                memoryId, alert.service(), alert.message(), alert.metric(), alert.value());
        LOG.infof("[L3:diagnose] evidence gathered | incident=%s", memoryId);

        var routed = severityRouter.route(memoryId,
                alert.service(), alert.message(), alert.metric(), alert.value(),
                severity, evidence.toPromptText());

        IncidentResult result = fromScope(routed.agenticScope(), severity);
        LOG.infof("[L3:diagnose] pipeline done | incident=%s severity=%s rootCause=%s remediation=%s destructive=%s",
                memoryId, result.severity(),
                result.diagnosis() != null ? result.diagnosis().rootCause() : null,
                result.remediation() != null ? result.remediation().type() : null,
                result.isRemediationDestructive());
        return result;
    }

    private static boolean isDeep(Severity severity) {
        return severity == Severity.P1_CRITICAL || severity == Severity.P2_HIGH;
    }

    public static IncidentResult fromScope(AgenticScope scope, Severity fallbackSeverity) {
        Severity severity = scope.readState("severity", fallbackSeverity);
        Diagnosis diagnosis = scope.readState("diagnosis", (Diagnosis) null);
        RemediationAction remediation = scope.readState("remediation", (RemediationAction) null);
        String score = scope.readState("score", "");
        return new IncidentResult(severity, diagnosis, remediation, ConfidenceScore.parse(score),
                null, null);
    }
}
