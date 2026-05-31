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
class IncidentResourceLayer3RealLlmTest {

    @BeforeEach
    void configureTimeouts() {
        RestAssured.config = RestAssured.config().httpClient(
                httpClientConfig().setParam("http.socket.timeout", 300000));
    }

    @Test
    void startWorkflow_withRealLlm_returnsAccepted() {
        given()
                .contentType("application/json")
                .body("""
                {
                    "source": "pagerduty",
                    "service": "payment-service",
                    "metric": "database_connectivity",
                    "value": 0.0,
                    "threshold": 1.0,
                    "message": "Database connectivity lost - payment service down"
                }
                """)
                .when()
                .post("/incidents/workflow")
                .then()
                .statusCode(202)
                .body("layer", equalTo("L3_FLOW"))
                .body("incidentId", notNullValue())
                .body("workflowInstanceId", notNullValue())
                .body("status", equalTo("STARTED"));
    }

    @Test
    void startWorkflow_withRealLlm_persistsIncidentWithWorkflowId() {
        String incidentId = given()
                .contentType("application/json")
                .body("""
                {
                    "source": "pagerduty",
                    "service": "payment-service",
                    "metric": "database_connectivity",
                    "value": 0.0,
                    "threshold": 1.0,
                    "message": "Database connectivity lost"
                }
                """)
                .post("/incidents/workflow")
                .then()
                .extract().path("incidentId");

        given()
                .get("/incidents/" + incidentId)
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("workflowInstanceId", notNullValue());
    }
}
