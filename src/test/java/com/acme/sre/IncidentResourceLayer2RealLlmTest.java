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

@Tag("real-llm")
@QuarkusTest
class IncidentResourceLayer2RealLlmTest {

    @BeforeEach
    void configureTimeouts() {
        RestAssured.config = RestAssured.config().httpClient(
                httpClientConfig().setParam("http.socket.timeout", 600000));
    }

    @Test
    void agenticAnalysis_withRealLlm_returnsStructuredResult() {
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
                .body("result", notNullValue())
                .body("result.severity", notNullValue())
                .body("result.diagnosis", notNullValue())
                .body("result.remediation", notNullValue());
    }

    @Test
    void agenticAnalysis_withRealLlm_persistsIncidentAsResolved() {
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
                .statusCode(200)
                .extract().path("incidentId");

        given()
                .get("/incidents/" + incidentId)
                .then()
                .statusCode(200)
                .body("status", equalTo("RESOLVED"));
    }
}
