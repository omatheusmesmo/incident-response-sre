package com.acme.sre.ai;

import com.acme.sre.domain.Diagnosis;
import com.acme.sre.domain.RemediationAction;
import com.acme.sre.domain.Severity;

import dev.langchain4j.agentic.declarative.ExitCondition;
import dev.langchain4j.agentic.declarative.LoopAgent;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;

public interface DiagnosticLoopAgent {

    @LoopAgent(
        description = "Iteratively diagnose and refine until confidence is high enough",
        outputKey = "loopResult",
        maxIterations = 3,
        subAgents = {DiagnosticAgent.class, RemediationAgent.class, ConfidenceScorer.class})
    ResultWithAgenticScope<String> diagnoseWithLoop(@MemoryId String memoryId,
            @V("service") String service,
            @V("message") String message,
            @V("severity") Severity severity);

    @ExitCondition(testExitAtLoopEnd = true)
    static boolean confidenceSufficient(@V("score") String score) {
        if (score == null || score.isBlank()) {
            return false;
        }
        try {
            return Double.parseDouble(score.trim()) >= 0.8;
        } catch (NumberFormatException e) {
            var matcher = java.util.regex.Pattern.compile("(\\d+\\.?\\d*)").matcher(score);
            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1)) >= 0.8;
            }
            return false;
        }
    }
}
