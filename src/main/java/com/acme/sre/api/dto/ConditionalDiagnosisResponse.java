package com.acme.sre.api.dto;

import com.acme.sre.domain.diagnosis.IncidentResult;

/** Layer 2 response: severity-routed diagnosis ({@code @ConditionalAgent}). */
public record ConditionalDiagnosisResponse(
        String layer,
        String pattern,
        String branch,
        String incidentId,
        IncidentResult result) {

    public static ConditionalDiagnosisResponse of(String branch, String incidentId, IncidentResult result) {
        return new ConditionalDiagnosisResponse("L2_CONDITIONAL",
                "@ConditionalAgent (severity router)", branch, incidentId, result);
    }
}
