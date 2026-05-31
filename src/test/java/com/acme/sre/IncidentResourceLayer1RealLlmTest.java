package com.acme.sre;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.config.HttpClientConfig.httpClientConfig;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@Tag("real-llm")
@QuarkusTest
class IncidentResourceLayer1RealLlmTest {

    @BeforeEach
    void configureTimeouts() {
        RestAssured.config = RestAssured.config().httpClient(
                httpClientConfig().setParam("http.socket.timeout", 300000));
    }

    @Test
    void analyzeAlert_withRealLlm_returnsStructuredAnalysis() {
        given()
                .contentType("application/json")
                .body("""
                {
                    "source": "prometheus",
                    "service": "api-gateway",
                    "metric": "error_rate",
                    "value": 15.5,
                    "threshold": 5.0,
                    "message": "Error rate exceeded threshold: 15.5% > 5.0%"
                }
                """)
                .when()
                .post("/incidents/alert")
                .then()
                .statusCode(200)
                .body("layer", equalTo("L1_CHATMODEL"))
                .body("incidentId", notNullValue())
                .body("alertId", notNullValue())
                .body("analysis.severity", notNullValue())
                .body("analysis.probableCause", notNullValue())
                .body("analysis.suggestedAction", notNullValue());
    }

    @Test
    void analyzeAlert_withRealLlm_persistsAndReturnsIncident() {
        String incidentId = given()
                .contentType("application/json")
                .body("""
                {
                    "source": "datadog",
                    "service": "payment-service",
                    "metric": "connection_pool_usage",
                    "value": 99.0,
                    "threshold": 80.0,
                    "message": "Connection pool at 99% capacity"
                }
                """)
                .post("/incidents/alert")
                .then()
                .statusCode(200)
                .extract().path("incidentId");

        given()
                .when()
                .get("/incidents/" + incidentId)
                .then()
                .statusCode(200)
                .body("id", equalTo(incidentId))
                .body("status", equalTo("TRIAGED"));
    }
}
