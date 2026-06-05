package com.acme.sre;

import java.util.Map;

import com.acme.sre.ai.commander.IncidentCommander;
import com.acme.sre.ai.diagnostics.DiagnosticLoopAgent;
import com.acme.sre.ai.diagnostics.EvidenceGatherer;
import com.acme.sre.ai.diagnostics.SeverityClassifier;
import com.acme.sre.ai.diagnostics.SeverityRouter;
import com.acme.sre.domain.ActionType;
import com.acme.sre.domain.Diagnosis;
import com.acme.sre.domain.Evidence;
import com.acme.sre.domain.RemediationAction;
import com.acme.sre.domain.Severity;

import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Deterministic coverage of the Layer 2 per-pattern endpoints. The agentic beans
 * (@ParallelAgent, @ConditionalAgent, @LoopAgent, @SupervisorAgent) are mocked so the test
 * exercises the REST wiring and the AgenticScope -> IncidentResult mapping without any LLM call.
 */
@QuarkusTest
class IncidentResourceLayer2Test {

    @InjectMock
    SeverityClassifier severityClassifier;

    @InjectMock
    EvidenceGatherer evidenceGatherer;

    @InjectMock
    SeverityRouter severityRouter;

    @InjectMock
    DiagnosticLoopAgent diagnosticLoopAgent;

    @InjectMock
    IncidentCommander incidentCommander;

    private static final String ALERT = """
            {
                "source": "prometheus",
                "service": "api-gateway",
                "metric": "error_rate",
                "value": 15.5,
                "threshold": 5.0,
                "message": "Error rate exceeded threshold"
            }
            """;

    @BeforeEach
    void setUp() {
        Mockito.when(severityClassifier.classify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Severity.P2_HIGH);

        Mockito.when(evidenceGatherer.gather(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new Evidence(
                        "Repeated OOM stack traces in cache module",
                        "Heap usage ramping to 97% after 14:00",
                        "Deploy v2.3 rolled out at 13:58"));

        ResultWithAgenticScope<String> routed = new ResultWithAgenticScope<>(diagnosedScope(), "deepResult");
        Mockito.when(severityRouter.route(anyString(), anyString(), anyString(), anyString(),
                        anyString(), any(Severity.class), anyString()))
                .thenReturn(routed);

        ResultWithAgenticScope<String> looped = new ResultWithAgenticScope<>(diagnosedScope(), "loopResult");
        Mockito.when(diagnosticLoopAgent.diagnoseWithLoop(anyString(), anyString(), anyString(),
                        any(Severity.class), anyString()))
                .thenReturn(looped);

        Mockito.when(incidentCommander.command(anyString()))
                .thenReturn("Most probable root cause: connection pool exhaustion. Action: raise pool size and add backpressure.");
    }

    private AgenticScope diagnosedScope() {
        AgenticScope scope = Mockito.mock(AgenticScope.class);
        Mockito.when(scope.readState(eq("severity"), any())).thenReturn(Severity.P2_HIGH);
        Mockito.when(scope.readState(eq("diagnosis"), any())).thenReturn(
                new Diagnosis("Memory leak in cache module", "Cache entries never evicted", "api-gateway-cache"));
        Mockito.when(scope.readState(eq("remediation"), any())).thenReturn(
                new RemediationAction(ActionType.RESTART_POD, "Restart api-gateway", true, "api-gateway", Map.of()));
        Mockito.when(scope.readState(eq("score"), any())).thenReturn("0.85");
        return scope;
    }

    @Test
    void parallel_returnsFannedOutEvidence() {
        given().contentType("application/json").body(ALERT)
                .when().post("/incidents/parallel")
                .then().statusCode(200)
                .body("layer", equalTo("L2_PARALLEL"))
                .body("incidentId", notNullValue())
                .body("evidence.logsFindings", notNullValue())
                .body("evidence.metricsFindings", notNullValue())
                .body("evidence.deployFindings", notNullValue());
    }

    @Test
    void conditional_routesDeepAndMapsScopeToResult() {
        given().contentType("application/json").body(ALERT)
                .when().post("/incidents/conditional")
                .then().statusCode(200)
                .body("layer", equalTo("L2_CONDITIONAL"))
                .body("branch", equalTo("deep"))
                .body("result.severity", equalTo("P2_HIGH"))
                .body("result.diagnosis.rootCause", equalTo("Memory leak in cache module"))
                .body("result.confidenceScore.score", equalTo(0.85f));
    }

    @Test
    void loop_mapsScopeToResult() {
        given().contentType("application/json").body(ALERT)
                .when().post("/incidents/loop")
                .then().statusCode(200)
                .body("layer", equalTo("L2_LOOP"))
                .body("result.diagnosis.rootCause", equalTo("Memory leak in cache module"))
                .body("result.confidenceScore.score", equalTo(0.85f));
    }

    @Test
    void commander_returnsSupervisorAssessment() {
        given().contentType("application/json").body(ALERT)
                .when().post("/incidents/commander")
                .then().statusCode(200)
                .body("layer", equalTo("L2_SUPERVISOR"))
                .body("incidentId", notNullValue())
                .body("assessment", notNullValue());
    }
}
