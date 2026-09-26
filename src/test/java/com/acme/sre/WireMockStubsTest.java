package com.acme.sre;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

/**
 * Verifies the external integrations are served by the WireMock dev service
 * (Prometheus / deployment controller / Slack), replacing the old in-app mocks.
 */
@QuarkusTest
class WireMockStubsTest {

    @ConfigProperty(name = "quarkus.wiremock.devservices.port")
    int wiremockPort;

    private String wiremock() {
        return "http://localhost:" + wiremockPort;
    }

    @Test
    void prometheus_returnsMetrics() {
        RestAssured.given().baseUri(wiremock())
                .queryParam("query", "cpu_usage")
                .when().get("/prometheus/query")
                .then().statusCode(200)
                .body("service", notNullValue())
                .body("cpuUsage", notNullValue())
                .body("errorRate", notNullValue());
    }

    @Test
    void deployment_acceptsRemediation() {
        given().baseUri(wiremock())
                .contentType("application/json")
                .body("{\"type\":\"RESTART_POD\",\"targetService\":\"api-gateway\"}")
                .when().post("/deployment/remediate")
                .then().statusCode(200)
                .body("status", equalTo("COMPLETED"));
    }

    @Test
    void slack_acceptsNotification() {
        given().baseUri(wiremock())
                .contentType("application/json")
                .body("{\"channel\":\"#sre-alerts\",\"message\":\"test\"}")
                .when().post("/slack/notify")
                .then().statusCode(200)
                .body("ok", equalTo(true));
    }

    @Test
    void prometheus_returnsTheQueriedServicesOwnMetrics() {
        given().baseUri(wiremock())
                .queryParam("service", "payments-api")
                .when().get("/prometheus/query")
                .then().statusCode(200)
                .body("service", equalTo("payments-api"))
                .body("memoryUsage", equalTo(97.0f));
    }

    @Test
    void logsAndDeployments_tellOneStoryPerService() {
        given().baseUri(wiremock())
                .queryParam("service", "payment-service")
                .when().get("/logs")
                .then().statusCode(200)
                .body("message", hasItem(containsString("LedgerService")));

        given().baseUri(wiremock())
                .queryParam("service", "payment-service")
                .when().get("/deployments")
                .then().statusCode(200)
                .body("[0].version", equalTo("v2.3.0"))
                .body("[0].change", containsString("LedgerService"));
    }

    @Test
    void unknownService_fallsBackToEmptyHistory() {
        given().baseUri(wiremock())
                .queryParam("service", "not-a-service")
                .when().get("/logs")
                .then().statusCode(200)
                .body("size()", equalTo(0));
    }
}
