package com.acme.sre.ai.diagnostics;

import com.acme.sre.domain.model.Severity;

import dev.langchain4j.agentic.agent.AgentInvocationException;
import dev.langchain4j.agentic.agent.ErrorContext;
import dev.langchain4j.agentic.agent.ErrorRecoveryResult;
import dev.langchain4j.agentic.agent.MissingArgumentException;
import dev.langchain4j.agentic.declarative.ErrorHandler;
import dev.langchain4j.agentic.declarative.ExitCondition;
import dev.langchain4j.agentic.declarative.LoopAgent;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface DiagnosticLoopAgent {

    @LoopAgent(
        description = "Iteratively diagnose and refine until confidence is high enough",
        outputKey = "loopResult",
        maxIterations = 3,
        subAgents = {DiagnosticAgent.class, RemediationAgent.class, ConfidenceScorer.class})
    ResultWithAgenticScope<String> diagnoseWithLoop(@MemoryId String memoryId,
            @V("service") String service,
            @V("message") String message,
            @V("severity") Severity severity,
            @V("evidenceText") String evidenceText);

    @ErrorHandler
    static ErrorRecoveryResult onError(ErrorContext errorContext) {
        AgentInvocationException exception = errorContext.exception();

        if (exception instanceof MissingArgumentException missing) {
            switch (missing.argumentName()) {
                case "evidenceText" -> errorContext.agenticScope()
                        .writeState("evidenceText", "No evidence available (collection failed).");
                case "score" -> errorContext.agenticScope().writeState("score", "0.0");
                case "severity" -> errorContext.agenticScope()
                        .writeState("severity", Severity.P3_MEDIUM);
                default -> {
                    return ErrorRecoveryResult.throwException();
                }
            }
            return ErrorRecoveryResult.retry();
        }

        String reason = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();
        boolean transientLlm = reason.contains("timeout") || reason.contains("429")
                || reason.contains("rate limit") || reason.contains("temporarily");
        int attempts = errorContext.agenticScope().readState("loopErrorRetries", 0);
        if (transientLlm && attempts < 1) {
            errorContext.agenticScope().writeState("loopErrorRetries", attempts + 1);
            return ErrorRecoveryResult.retry();
        }

        errorContext.agenticScope().writeState("score", "0.0");
        return ErrorRecoveryResult.result(
                "Diagnostic loop degraded: agent '" + errorContext.agentName() + "' failed ("
                        + exception.getClass().getSimpleName() + "). Returning best-effort partial result.");
    }

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
