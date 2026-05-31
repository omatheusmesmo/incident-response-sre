package com.acme.sre;

import com.acme.sre.flow.IncidentResponseFlow;

import io.serverlessworkflow.impl.WorkflowInstance;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class IncidentResourceLayer3Test {

    @InjectMock
    IncidentResponseFlow layer3Flow;

    @Test
    void startWorkflow_returnsAccepted() {
        WorkflowInstance mockInstance = Mockito.mock(WorkflowInstance.class);
        Mockito.when(mockInstance.id()).thenReturn("wf-test-123");
        Mockito.when(layer3Flow.instance(Mockito.any())).thenReturn(mockInstance);

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
    void startWorkflow_persistsIncidentWithWorkflowId() {
        WorkflowInstance mockInstance = Mockito.mock(WorkflowInstance.class);
        Mockito.when(mockInstance.id()).thenReturn("wf-test-456");
        Mockito.when(layer3Flow.instance(Mockito.any())).thenReturn(mockInstance);

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

    @Test
    void approve_nonExistentIncident_returns404() {
        given()
                .contentType("application/json")
                .body("""
                {
                    "incidentId": "non-existent-id",
                    "approved": true,
                    "reviewer": "sre-lead",
                    "reason": "Approved for production"
                }
                """)
                .when()
                .put("/incidents/non-existent-id/approve")
                .then()
                .statusCode(404);
    }

    @Test
    void reject_nonExistentIncident_returns404() {
        given()
                .contentType("application/json")
                .body("""
                {
                    "incidentId": "non-existent-id",
                    "approved": false,
                    "reviewer": "sre-lead",
                    "reason": "Too risky during peak hours"
                }
                """)
                .when()
                .put("/incidents/non-existent-id/reject")
                .then()
                .statusCode(404);
    }
}
