package com.acme.sre.api.dto;

import com.acme.sre.domain.diagnosis.IncidentResult;

/** Layer 2 response: iterative diagnosis refined to a confidence threshold ({@code @LoopAgent}). */
public record LoopDiagnosisResponse(
        String layer,
        String pattern,
        String incidentId,
        IncidentResult result) {

    public static LoopDiagnosisResponse of(String incidentId, IncidentResult result) {
        return new LoopDiagnosisResponse("L2_LOOP",
                "@LoopAgent (diagnose -> remediate -> score, refine until confidence >= 0.8)",
                incidentId, result);
    }
}
