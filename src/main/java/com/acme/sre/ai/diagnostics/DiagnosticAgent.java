package com.acme.sre.ai.diagnostics;

import com.acme.sre.domain.diagnosis.Diagnosis;
import com.acme.sre.domain.model.Severity;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface DiagnosticAgent {

    @Agent(description = "Diagnoses the root cause of an incident, correlating evidence gathered in parallel", outputKey = "diagnosis")
    @SystemMessage("""
        You are a senior SRE diagnostician. Given alert data AND the evidence gathered from
        logs, metrics, and deploy history, identify the most probable root cause.
        Weigh the three evidence streams: a recent deploy plus a metric ramp points to a regression;
        a slow memory ramp with GC pressure points to a leak; saturation points to capacity.
        Be specific: name the exact component, module, or configuration that is likely failing.

        You MUST return valid JSON with no trailing commas:
        {"rootCause": "specific technical root cause", "explanation": "why this is the most likely cause", "affectedComponent": "specific component or service name"}
        """)
    @UserMessage("""
        Service: {service}, Alert: {message}, Severity: {severity}
        Evidence (logs, metrics, deploy history): {evidenceText}
        """)
    Diagnosis diagnose(@V("service") String service,
            @V("message") String message,
            @V("severity") Severity severity,
            @V("evidenceText") String evidenceText);
}
