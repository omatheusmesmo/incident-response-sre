package com.acme.sre;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.config.HttpClientConfig.httpClientConfig;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * End-to-end Layer 2 pattern tests against a real LLM (tagged real-llm, excluded by default).
 * Run with: mvn test -Preal-llm
 */
@Tag("real-llm")
@QuarkusTest
class IncidentResourceLayer2RealLlmTest {

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
    void configureTimeouts() {
        RestAssured.config = RestAssured.config().httpClient(
                httpClientConfig().setParam("http.socket.timeout", 600000));
    }

    @Test
    void parallel_withRealLlm_returnsEvidence() {
        given().contentType("application/json").body(ALERT)
                .when().post("/incidents/parallel")
                .then().statusCode(200)
                .body("layer", equalTo("L2_PARALLEL"))
                .body("evidence.logsFindings", notNullValue())
                .body("evidence.metricsFindings", notNullValue())
                .body("evidence.deployFindings", notNullValue());
    }

    @Test
    void conditional_withRealLlm_returnsResult() {
        given().contentType("application/json").body(ALERT)
                .when().post("/incidents/conditional")
                .then().statusCode(200)
                .body("layer", equalTo("L2_CONDITIONAL"))
                .body("branch", notNullValue())
                .body("result.severity", notNullValue())
                .body("result.diagnosis", notNullValue());
    }

    @Test
    void loop_withRealLlm_returnsResult() {
        given().contentType("application/json").body(ALERT)
                .when().post("/incidents/loop")
                .then().statusCode(200)
                .body("layer", equalTo("L2_LOOP"))
                .body("result.diagnosis", notNullValue())
                .body("result.confidenceScore", notNullValue());
    }

    @Test
    void commander_withRealLlm_returnsAssessment() {
        given().contentType("application/json").body(ALERT)
                .when().post("/incidents/commander")
                .then().statusCode(200)
                .body("layer", equalTo("L2_SUPERVISOR"))
                .body("assessment", notNullValue());
    }
}
