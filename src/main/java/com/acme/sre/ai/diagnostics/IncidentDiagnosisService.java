package com.acme.sre.ai.diagnostics;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.acme.sre.domain.ConfidenceScore;
import com.acme.sre.domain.Diagnosis;
import com.acme.sre.domain.Evidence;
import com.acme.sre.domain.IncidentResult;
import com.acme.sre.domain.RemediationAction;
import com.acme.sre.domain.Severity;
import com.acme.sre.domain.TriagePrompt;

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

    @Inject
    SeverityClassifier severityClassifier;

    @Inject
    EvidenceGatherer evidenceGatherer;

    @Inject
    SeverityRouter severityRouter;

    public IncidentResult diagnose(TriagePrompt alert) {
        String memoryId = alert.incidentId() != null ? alert.incidentId() : UUID.randomUUID().toString();

        Severity severity = severityClassifier.classify(
                alert.message(), alert.service(), alert.metric(), alert.value());
        Evidence evidence = evidenceGatherer.gather(
                memoryId, alert.service(), alert.message(), alert.metric(), alert.value());
        var routed = severityRouter.route(memoryId,
                alert.service(), alert.message(), alert.metric(), alert.value(),
                severity, evidence.toPromptText());

        return fromScope(routed.agenticScope(), severity);
    }

    public static IncidentResult fromScope(AgenticScope scope, Severity fallbackSeverity) {
        Severity severity = scope.readState("severity", fallbackSeverity);
        Diagnosis diagnosis = scope.readState("diagnosis", (Diagnosis) null);
        RemediationAction remediation = scope.readState("remediation", (RemediationAction) null);
        String score = scope.readState("score", "");
        return new IncidentResult(severity, diagnosis, remediation, ConfidenceScore.parse(score),
                "Post-incident summary is produced by the durable workflow (Layer 3).");
    }
}
