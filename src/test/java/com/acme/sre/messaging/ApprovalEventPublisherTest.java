package com.acme.sre.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;

import com.acme.sre.domain.contract.ApprovalResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.reactive.messaging.ce.OutgoingCloudEventMetadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quarkus Flow only resumes a workflow waiting on {@code listen} when the incoming Kafka record
 * carries CloudEvent metadata ({@code ce_*} headers). A CloudEvent serialized into the record body
 * is silently dropped, which left every HITL approval unanswered.
 */
class ApprovalEventPublisherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CapturingEmitter emitter = new CapturingEmitter();
    private final ApprovalEventPublisher publisher = new ApprovalEventPublisher(objectMapper, emitter);

    @Test
    void publishesDecisionWithCloudEventMetadataCorrelatedToTheWorkflow() throws Exception {
        publisher.publishDecision("wf-123", new ApprovalResponse("incident-1", true, "sre", "looks right"));

        assertEquals(1, emitter.sent.size());
        Message<byte[]> message = emitter.sent.get(0);

        OutgoingCloudEventMetadata<?> ce = message.getMetadata(OutgoingCloudEventMetadata.class).orElseThrow();
        assertEquals("com.acme.sre.incident.approval.done", ce.getType());
        assertEquals("wf-123", ce.getExtension("flowinstanceid").orElseThrow());
        assertEquals("application/json", ce.getDataContentType().orElseThrow());
        assertFalse(ce.getId().isBlank());

        JsonNode payload = objectMapper.readTree(message.getPayload());
        assertTrue(payload.get("approved").asBoolean());
        assertEquals("incident-1", payload.get("incidentId").asText());
        assertFalse(payload.has("specversion"), "payload must be the decision, not a CloudEvent envelope");
    }

    @Test
    void eachDecisionGetsItsOwnEventId() {
        publisher.publishDecision("wf-1", new ApprovalResponse("incident-1", false, "sre", "no"));
        publisher.publishDecision("wf-1", new ApprovalResponse("incident-1", false, "sre", "no"));

        String first = emitter.sent.get(0).getMetadata(OutgoingCloudEventMetadata.class).orElseThrow().getId();
        String second = emitter.sent.get(1).getMetadata(OutgoingCloudEventMetadata.class).orElseThrow().getId();
        assertFalse(first.equals(second));
    }

    private static final class CapturingEmitter implements Emitter<byte[]> {
        private final List<Message<byte[]>> sent = new ArrayList<>();

        @Override
        public CompletionStage<Void> send(byte[] payload) {
            sent.add(Message.of(payload));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <M extends Message<? extends byte[]>> void send(M message) {
            sent.add((Message<byte[]>) message);
        }

        @Override
        public void complete() {
        }

        @Override
        public void error(Exception e) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean hasRequests() {
            return true;
        }
    }
}
