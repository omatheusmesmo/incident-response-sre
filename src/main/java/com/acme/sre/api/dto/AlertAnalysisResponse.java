package com.acme.sre.api.dto;

import com.acme.sre.domain.diagnosis.IncidentAnalysis;

/** Layer 1 response: the single-call ChatModel analysis of an alert. */
public record AlertAnalysisResponse(
        String layer,
        String incidentId,
        String alertId,
        IncidentAnalysis analysis) {

    public static AlertAnalysisResponse of(String incidentId, String alertId, IncidentAnalysis analysis) {
        return new AlertAnalysisResponse("L1_CHATMODEL", incidentId, alertId, analysis);
    }
}
