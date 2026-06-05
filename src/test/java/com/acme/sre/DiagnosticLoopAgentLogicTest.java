package com.acme.sre;

import com.acme.sre.ai.diagnostics.DiagnosticLoopAgent;
import com.acme.sre.domain.model.Severity;

import dev.langchain4j.agentic.agent.AgentInvocationException;
import dev.langchain4j.agentic.agent.ErrorContext;
import dev.langchain4j.agentic.agent.ErrorRecoveryResult;
import dev.langchain4j.agentic.agent.MissingArgumentException;
import dev.langchain4j.agentic.scope.AgenticScope;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Unit tests for the deterministic static methods of the @LoopAgent: the exit condition
 * that drives loop termination and the @ErrorHandler that provides agentic resilience.
 * No LLM or CDI container is needed: both are pure functions over their inputs.
 */
class DiagnosticLoopAgentLogicTest {

    @Test
    void exitCondition_trueOnlyWhenScoreReachesThreshold() {
        assertTrue(DiagnosticLoopAgent.confidenceSufficient("0.8"));
        assertTrue(DiagnosticLoopAgent.confidenceSufficient("0.95"));
        assertFalse(DiagnosticLoopAgent.confidenceSufficient("0.79"));
    }

    @Test
    void exitCondition_handlesProseAndBlankScores() {
        assertTrue(DiagnosticLoopAgent.confidenceSufficient("Confidence: 0.91 (clear cause)"));
        assertFalse(DiagnosticLoopAgent.confidenceSufficient(""));
        assertFalse(DiagnosticLoopAgent.confidenceSufficient(null));
    }

    @Test
    void errorHandler_seedsMissingKnownArgumentAndRetries() {
        AgenticScope scope = Mockito.mock(AgenticScope.class);
        ErrorContext ctx = new ErrorContext("diagnosticAgent", scope,
                new MissingArgumentException("evidenceText"));

        ErrorRecoveryResult result = DiagnosticLoopAgent.onError(ctx);

        assertEquals(ErrorRecoveryResult.Type.RETRY, result.type());
        Mockito.verify(scope).writeState(eq("evidenceText"), any());
    }

    @Test
    void errorHandler_throwsForUnknownMissingArgument() {
        AgenticScope scope = Mockito.mock(AgenticScope.class);
        ErrorContext ctx = new ErrorContext("diagnosticAgent", scope,
                new MissingArgumentException("somethingUnexpected"));

        ErrorRecoveryResult result = DiagnosticLoopAgent.onError(ctx);

        assertEquals(ErrorRecoveryResult.Type.THROW_EXCEPTION, result.type());
    }

    @Test
    void errorHandler_retriesOnceForTransientLlmError() {
        AgenticScope scope = Mockito.mock(AgenticScope.class);
        Mockito.when(scope.readState(eq("loopErrorRetries"), any())).thenReturn(0);
        ErrorContext ctx = new ErrorContext("confidenceScorer", scope,
                new AgentInvocationException("Request timeout while calling the model"));

        ErrorRecoveryResult result = DiagnosticLoopAgent.onError(ctx);

        assertEquals(ErrorRecoveryResult.Type.RETRY, result.type());
        Mockito.verify(scope).writeState(eq("loopErrorRetries"), eq(1));
    }

    @Test
    void errorHandler_degradesGracefullyForNonTransientError() {
        AgenticScope scope = Mockito.mock(AgenticScope.class);
        Mockito.when(scope.readState(eq("loopErrorRetries"), any())).thenReturn(0);
        ErrorContext ctx = new ErrorContext("diagnosticAgent", scope,
                new AgentInvocationException("invalid JSON returned by the model"));

        ErrorRecoveryResult result = DiagnosticLoopAgent.onError(ctx);

        assertEquals(ErrorRecoveryResult.Type.RETURN_RESULT, result.type());
        Mockito.verify(scope).writeState(eq("score"), eq("0.0"));
    }
}
