package com.acme.sre.ai.diagnostics;

import com.acme.sre.domain.Diagnosis;
import com.acme.sre.domain.Severity;

import dev.langchain4j.agentic.declarative.Output;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface LightTriageAgent {

    @SequenceAgent(
        description = "Lightweight triage path for low-severity incidents: quick diagnosis, a non-disruptive action, then a confidence score",
        outputKey = "lightResult",
        subAgents = {LightDiagnosisAgent.class, LightRemediationAgent.class, ConfidenceScorer.class})
    String triage(@MemoryId String memoryId,
            @V("service") String service,
            @V("message") String message,
            @V("severity") Severity severity);

    @Output
    static String summarize(@V("diagnosis") Diagnosis diagnosis) {
        return diagnosis != null
                ? "Light triage complete: " + diagnosis.rootCause()
                : "Light triage complete";
    }
}
