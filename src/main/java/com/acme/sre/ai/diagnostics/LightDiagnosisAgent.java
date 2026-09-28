package com.acme.sre.ai.diagnostics;

import com.acme.sre.ai.support.AgentMemory;
import com.acme.sre.domain.diagnosis.Diagnosis;
import com.acme.sre.domain.model.Severity;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatMemoryProviderSupplier;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface LightDiagnosisAgent {

    @Agent(description = "Quick root-cause assessment for low-severity incidents", outputKey = "diagnosis")
    @SystemMessage("""
        You are an SRE doing a QUICK triage of a LOW severity incident.
        Provide a brief probable cause without deep investigation. Keep it proportionate to the low impact.

        You MUST return valid JSON with no trailing commas:
        {"rootCause": "brief probable cause", "explanation": "one short sentence", "affectedComponent": "component or service name"}
        """)
    @UserMessage("Service: {service}, Alert: {message}, Severity: {severity}. Quick assessment.")
    Diagnosis diagnose(@MemoryId String memoryId,
            @V("service") String service,
            @V("message") String message,
            @V("severity") Severity severity);

    @ChatMemoryProviderSupplier
    static ChatMemory chatMemory(Object memoryId) {
        return AgentMemory.perIncident(memoryId);
    }
}
