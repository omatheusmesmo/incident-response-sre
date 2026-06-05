package com.acme.sre.ai.diagnostics;

import com.acme.sre.domain.Severity;

import dev.langchain4j.agentic.declarative.ActivationCondition;
import dev.langchain4j.agentic.declarative.ConditionalAgent;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface SeverityRouter {

    @ConditionalAgent(
        description = "Routes diagnosis depth by severity: P1/P2 get deep evidence-driven diagnosis, P3/P4 get light triage",
        outputKey = "routedResult",
        subAgents = {DeepDiagnosisAgent.class, LightTriageAgent.class})
    ResultWithAgenticScope<String> route(@MemoryId String memoryId,
            @V("service") String service,
            @V("message") String message,
            @V("metric") String metric,
            @V("value") String value,
            @V("severity") Severity severity,
            @V("evidenceText") String evidenceText);

    @ActivationCondition(DeepDiagnosisAgent.class)
    static boolean activateDeep(@V("severity") Severity severity) {
        return severity == null || severity == Severity.P1_CRITICAL || severity == Severity.P2_HIGH;
    }

    @ActivationCondition(LightTriageAgent.class)
    static boolean activateLight(@V("severity") Severity severity) {
        return severity == Severity.P3_MEDIUM || severity == Severity.P4_LOW;
    }
}
