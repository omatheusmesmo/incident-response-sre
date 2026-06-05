package com.acme.sre.api.dto;

/** Layer 2 response: supervisor-planned multi-specialist assessment ({@code @SupervisorAgent}). */
public record CommanderResponse(
        String layer,
        String pattern,
        String incidentId,
        String assessment) {

    public static CommanderResponse of(String incidentId, String assessment) {
        return new CommanderResponse("L2_SUPERVISOR",
                "@SupervisorAgent (LLM-planned routing across domain specialists)", incidentId, assessment);
    }
}
