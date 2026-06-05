package com.acme.sre.ai.diagnostics;

import com.acme.sre.domain.model.Severity;

import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface DeepDiagnosisAgent {

    @SequenceAgent(
        description = "Deep diagnosis path for high-severity incidents: correlate the parallel evidence into a root cause, propose remediation, then score confidence",
        outputKey = "deepResult",
        subAgents = {DiagnosticAgent.class, RemediationAgent.class, ConfidenceScorer.class})
    String diagnose(@MemoryId String memoryId,
            @V("service") String service,
            @V("message") String message,
            @V("severity") Severity severity);
}
