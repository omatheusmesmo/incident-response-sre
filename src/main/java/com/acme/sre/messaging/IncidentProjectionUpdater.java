package com.acme.sre.messaging;

import com.acme.sre.domain.model.ActionType;
import com.acme.sre.domain.model.Incident;
import com.acme.sre.domain.model.IncidentStatus;
import com.acme.sre.domain.model.Severity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Projects the durable workflow's emitted {@code IncidentResult} back onto the {@link Incident}
 * JPA record so the REST view (GET /incidents/{id}) stays consistent with the live dashboard:
 * PENDING_APPROVAL when the workflow pauses for HITL, RESOLVED when it completes.
 */
@ApplicationScoped
public class IncidentProjectionUpdater {

    private static final Logger LOG = Logger.getLogger(IncidentProjectionUpdater.class);

    @Transactional
    public void apply(String workflowInstanceId, IncidentStatus status, JsonNode result) {
        if (workflowInstanceId == null) {
            return;
        }
        Incident incident = Incident.find("workflowInstanceId", workflowInstanceId).firstResult();
        if (incident == null) {
            return;
        }
        if (result != null) {
            if (result.hasNonNull("severity")) {
                incident.severity = parseSeverity(result.get("severity").asText());
            }
            JsonNode diagnosis = result.get("diagnosis");
            if (diagnosis != null && diagnosis.isObject()) {
                if (diagnosis.hasNonNull("rootCause")) {
                    incident.rootCause = diagnosis.get("rootCause").asText();
                }
                if (diagnosis.hasNonNull("explanation")) {
                    incident.diagnosis = diagnosis.get("explanation").asText();
                }
            }
            JsonNode remediation = result.get("remediation");
            if (remediation != null && remediation.isObject()) {
                if (remediation.hasNonNull("type")) {
                    incident.remediationType = parseAction(remediation.get("type").asText());
                }
                if (remediation.hasNonNull("description")) {
                    incident.remediationDescription = remediation.get("description").asText();
                }
                if (remediation.hasNonNull("destructive")) {
                    incident.remediationDestructive = remediation.get("destructive").asBoolean();
                }
                if (remediation.hasNonNull("targetService")) {
                    incident.remediationTargetService = remediation.get("targetService").asText();
                }
            }
            JsonNode score = result.get("confidenceScore");
            if (score != null && score.hasNonNull("score")) {
                incident.confidenceScore = score.get("score").asDouble();
            }
            if (result.hasNonNull("postMortemSummary")) {
                incident.postMortemSummary = result.get("postMortemSummary").asText();
            }
        }
        incident.status = status;
        incident.persist();
        LOG.infof("incident %s projected to %s (workflow %s)", incident.id, status, workflowInstanceId);
    }

    private static Severity parseSeverity(String value) {
        try {
            return Severity.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ActionType parseAction(String value) {
        try {
            return ActionType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
