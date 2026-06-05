package com.acme.sre;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

/**
 * Verifies the external integrations are served by the WireMock dev service
 * (Prometheus / deployment controller / Slack), replacing the old in-app mocks.
 */
@QuarkusTest
class WireMockStubsTest {

    private static final String WIREMOCK = "http://localhost:8089";

    @Test
    void prometheus_returnsMetrics() {
        RestAssured.given().baseUri(WIREMOCK)
                .queryParam("query", "cpu_usage")
                .when().get("/prometheus/query")
                .then().statusCode(200)
                .body("service", notNullValue())
                .body("cpuUsage", notNullValue())
                .body("errorRate", notNullValue());
    }

    @Test
    void deployment_acceptsRemediation() {
        given().baseUri(WIREMOCK)
                .contentType("application/json")
                .body("{\"type\":\"RESTART_POD\",\"targetService\":\"api-gateway\"}")
                .when().post("/deployment/remediate")
                .then().statusCode(200)
                .body("status", equalTo("COMPLETED"));
    }

    @Test
    void slack_acceptsNotification() {
        given().baseUri(WIREMOCK)
                .contentType("application/json")
                .body("{\"channel\":\"#sre-alerts\",\"message\":\"test\"}")
                .when().post("/slack/notify")
                .then().statusCode(200)
                .body("ok", equalTo(true));
    }
}
