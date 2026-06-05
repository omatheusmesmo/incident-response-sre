package com.acme.sre.api.dto;

import com.acme.sre.domain.model.ActionType;
import com.acme.sre.domain.model.Incident;
import com.acme.sre.domain.model.IncidentStatus;
import com.acme.sre.domain.model.Severity;

/** Read-model view of a persisted {@link Incident}, so the REST boundary never exposes the entity. */
public record IncidentView(
        String id,
        String alertSource,
        String alertService,
        String alertMetric,
        String alertMessage,
        Severity severity,
        String diagnosis,
        String rootCause,
        ActionType remediationType,
        String remediationDescription,
        boolean remediationDestructive,
        String remediationTargetService,
        IncidentStatus status,
        double confidenceScore,
        String postMortemSummary,
        String workflowInstanceId) {

    public static IncidentView from(Incident incident) {
        return new IncidentView(
                incident.id,
                incident.alertSource,
                incident.alertService,
                incident.alertMetric,
                incident.alertMessage,
                incident.severity,
                incident.diagnosis,
                incident.rootCause,
                incident.remediationType,
                incident.remediationDescription,
                incident.remediationDestructive,
                incident.remediationTargetService,
                incident.status,
                incident.confidenceScore,
                incident.postMortemSummary,
                incident.workflowInstanceId);
    }
}
