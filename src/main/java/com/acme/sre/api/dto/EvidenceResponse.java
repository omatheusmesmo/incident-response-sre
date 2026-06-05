package com.acme.sre.api.dto;

import com.acme.sre.domain.diagnosis.Evidence;

/** Layer 2 response: parallel evidence gathering ({@code @ParallelAgent}). */
public record EvidenceResponse(
        String layer,
        String pattern,
        String incidentId,
        Evidence evidence) {

    public static EvidenceResponse of(String incidentId, Evidence evidence) {
        return new EvidenceResponse("L2_PARALLEL",
                "@ParallelAgent (fan-out: logs + metrics + deploy history)", incidentId, evidence);
    }
}
