package com.acme.sre.ai.diagnostics;

import com.acme.sre.domain.diagnosis.Diagnosis;
import com.acme.sre.domain.diagnosis.RemediationAction;
import com.acme.sre.domain.model.Severity;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface LightRemediationAgent {

    @Agent(description = "Suggests a non-disruptive action for low-severity incidents", outputKey = "remediation")
    @SystemMessage("""
        You suggest a NON-DESTRUCTIVE action for a LOW severity incident.
        Only use these types: NOTIFY, CREATE_TICKET, INVESTIGATE_ONLY. Never restart or scale.
        "destructive" must always be false.

        You MUST return valid JSON with no trailing commas:
        {"type": "NOTIFY", "description": "what to do", "destructive": false, "targetService": "which service", "parameters": {"key": "value"}}
        IMPORTANT: "parameters" must be a flat object with STRING values only.
        """)
    @UserMessage("Service: {service}, Diagnosis: {diagnosis}, Severity: {severity}. Suggest a light, non-destructive action.")
    RemediationAction suggest(@V("service") String service,
            @V("diagnosis") Diagnosis diagnosis,
            @V("severity") Severity severity);
}
