package com.acme.sre;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class IncidentResourceLayer1Test {

    @InjectMock
    ChatModel chatModel;

    private void stubChatModel(String json) {
        Mockito.when(chatModel.chat(Mockito.any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(json))
                        .build());
    }

    @Test
    void analyzeAlert_returnsStructuredAnalysis() {
        stubChatModel("""
            {
              "severity": "P2_HIGH",
              "probableCause": "Memory leak in the cache module causing OOM errors",
              "suggestedAction": "Restart the cache service and increase heap allocation"
            }
            """);

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
                .body("alertId", notNullValue());
    }

    @Test
    void analyzeAlert_persistsAndReturnsIncident() {
        stubChatModel("""
            {
              "severity": "P1_CRITICAL",
              "probableCause": "Database connection pool exhausted",
              "suggestedAction": "Scale up database connections and restart connection pool"
            }
            """);

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

    @Test
    void getIncident_notFound() {
        given()
                .when()
                .get("/incidents/non-existent-id")
                .then()
                .statusCode(404);
    }
}
