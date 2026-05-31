package com.acme.sre.ai;

import com.acme.sre.domain.Diagnosis;
import com.acme.sre.domain.Severity;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface DiagnosticAgent {

    @Agent(description = "Diagnoses the root cause of an incident based on alert data", outputKey = "diagnosis")
    @SystemMessage("""
        You are a senior SRE diagnostician. Given alert data, identify the most probable root cause.
        Be specific: name the exact component, module, or configuration that is likely failing.

        You MUST return valid JSON with no trailing commas:
        {"rootCause": "specific technical root cause", "explanation": "why this is the most likely cause", "affectedComponent": "specific component or service name"}

        Consider common failure patterns: memory leaks, resource exhaustion, misconfiguration, deployment issues.
        """)
    @UserMessage("Service: {service}, Alert: {message}, Severity: {severity}")
    Diagnosis diagnose(@V("service") String service,
            @V("message") String message,
            @V("severity") Severity severity);
}
