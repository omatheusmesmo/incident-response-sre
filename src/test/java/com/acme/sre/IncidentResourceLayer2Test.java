package com.acme.sre;

import java.util.Map;

import com.acme.sre.ai.IncidentResponseAgent;
import com.acme.sre.domain.ActionType;
import com.acme.sre.domain.ConfidenceScore;
import com.acme.sre.domain.Diagnosis;
import com.acme.sre.domain.IncidentResult;
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

@QuarkusTest
class IncidentResourceLayer2Test {

    @InjectMock
    IncidentResponseAgent layer2Agent;

    ResultWithAgenticScope<IncidentResult> agenticResult;

    @BeforeEach
    void setUp() {
        IncidentResult result = new IncidentResult(
                Severity.P2_HIGH,
                new Diagnosis("Memory leak in cache module", "Heap analysis shows growing cache entries not being evicted", "api-gateway-cache"),
                new RemediationAction(ActionType.RESTART_POD, "Restart api-gateway to clear leaked cache entries", true, "api-gateway", Map.of()),
                new ConfidenceScore(0.85, "Clear root cause identified, concrete action proposed"),
                "Post-incident: Memory leak in cache module. Action: pod restart. Follow-up: patch eviction.");

        AgenticScope scope = Mockito.mock(AgenticScope.class);
        Mockito.when(scope.state()).thenReturn(Map.of(
                "severity", Severity.P2_HIGH,
                "diagnosis", result.diagnosis(),
                "remediation", result.remediation(),
                "score", result.confidenceScore(),
                "postMortem", result.postMortemSummary()));

        agenticResult = new ResultWithAgenticScope<>(scope, result);

        Mockito.when(layer2Agent.respond(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(agenticResult);
    }

    @Test
    void agenticAnalysis_returnsStructuredResult() {
        given()
                .contentType("application/json")
                .body("""
                {
                    "source": "prometheus",
                    "service": "api-gateway",
                    "metric": "error_rate",
                    "value": 15.5,
                    "threshold": 5.0,
                    "message": "Error rate exceeded threshold"
                }
                """)
                .when()
                .post("/incidents/analyze-agentic")
                .then()
                .statusCode(200)
                .body("layer", equalTo("L2_AGENTIC"))
                .body("incidentId", notNullValue())
                .body("result", notNullValue());
    }

    @Test
    void agenticAnalysis_persistsIncidentAsResolved() {
        String incidentId = given()
                .contentType("application/json")
                .body("""
                {
                    "source": "prometheus",
                    "service": "api-gateway",
                    "metric": "error_rate",
                    "value": 15.5,
                    "threshold": 5.0,
                    "message": "Error rate exceeded threshold"
                }
                """)
                .post("/incidents/analyze-agentic")
                .then()
                .extract().path("incidentId");

        given()
                .get("/incidents/" + incidentId)
                .then()
                .statusCode(200)
                .body("status", equalTo("RESOLVED"));
    }
}
