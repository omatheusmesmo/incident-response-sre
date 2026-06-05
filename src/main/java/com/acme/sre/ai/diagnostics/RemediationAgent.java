package com.acme.sre.ai.diagnostics;

import com.acme.sre.domain.diagnosis.Diagnosis;
import com.acme.sre.domain.diagnosis.RemediationAction;
import com.acme.sre.domain.model.Severity;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface RemediationAgent {

    @Agent(description = "Suggests a remediation action for the diagnosed incident", outputKey = "remediation")
    @SystemMessage("""
        You suggest concrete remediation actions for SRE incidents.

You MUST return valid JSON with no trailing commas:
{"type": "RESTART_POD", "description": "what to do", "destructive": true, "targetService": "which service", "parameters": {"key": "value"}}

IMPORTANT: "parameters" must be a flat object with STRING values only. Do NOT use arrays or nested objects as parameter values.
type is one of: RESTART_POD, SCALE_DOWN, SCALE_UP, FEATURE_FLAG, NOTIFY, CREATE_TICKET, INVESTIGATE_ONLY
destructive is true if the action could disrupt service (RESTART_POD and SCALE_DOWN are destructive).
For high-severity, prefer stabilizing actions. For low-severity, prefer investigation and notification.
        """)
    @UserMessage("Service: {service}, Diagnosis: {diagnosis}, Severity: {severity}. Suggest remediation.")
    RemediationAction suggest(@V("service") String service,
            @V("diagnosis") Diagnosis diagnosis,
            @V("severity") Severity severity);
}
