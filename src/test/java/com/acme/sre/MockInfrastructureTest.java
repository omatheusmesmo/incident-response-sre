package com.acme.sre;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class MockInfrastructureTest {

    @Test
    void mockPrometheus_returnsMetrics() {
        given()
                .queryParam("query", "cpu_usage")
                .when()
                .get("/mock/prometheus/query")
                .then()
                .statusCode(200)
                .body("status", equalTo("success"))
                .body("data", notNullValue());
    }

    @Test
    void mockPrometheus_returnsServiceMetrics() {
        given()
                .queryParam("service", "api-gateway")
                .when()
                .get("/mock/prometheus/metrics/api-gateway")
                .then()
                .statusCode(200)
                .body("service", equalTo("api-gateway"))
                .body("cpuUsage", notNullValue())
                .body("memoryUsage", notNullValue());
    }

    @Test
    void mockDeployment_executesRemediation() {
        given()
                .contentType("application/json")
                .body("""
                    {
                      "type": "RESTART_POD",
                      "targetService": "api-gateway",
                      "description": "Restart pod to clear cache"
                    }
                    """)
                .when()
                .post("/mock/deployment/remediate")
                .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("actionId", notNullValue());
    }

    @Test
    void mockSlack_sendsNotification() {
        given()
                .contentType("application/json")
                .body("""
                    {
                      "channel": "#sre-alerts",
                      "message": "Incident remediation executed"
                    }
                    """)
                .when()
                .post("/mock/slack/notify")
                .then()
                .statusCode(200)
                .body("status", equalTo("SENT"))
                .body("notificationId", notNullValue());
    }
}
