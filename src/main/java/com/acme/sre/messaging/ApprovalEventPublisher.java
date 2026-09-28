package com.acme.sre.messaging;

import java.net.URI;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.jboss.logging.Logger;

import com.acme.sre.domain.contract.ApprovalResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.reactive.messaging.ce.OutgoingCloudEventMetadata;

/**
 * Publishes the SRE approve/reject decision as a CloudEvent on {@code flow-in}, correlated to the
 * paused workflow instance via the {@code flowinstanceid} extension. This is what resumes a Layer 3
 * workflow waiting at its HITL {@code listen} gate.
 * <p>
 * The event attributes travel as {@link OutgoingCloudEventMetadata}, so the Kafka connector writes
 * them as {@code ce_*} record headers (binary mode). Quarkus Flow only accepts incoming messages that
 * carry {@code CloudEventMetadata}; a CloudEvent serialized into the record body without headers is
 * discarded and the workflow never resumes.
 */
@ApplicationScoped
public class ApprovalEventPublisher {

    private static final Logger LOG = Logger.getLogger(ApprovalEventPublisher.class);
    static final String APPROVAL_DONE_TYPE = "com.acme.sre.incident.approval.done";
    static final String INSTANCE_ID_EXTENSION = "flowinstanceid";

    private final ObjectMapper objectMapper;
    private final Emitter<byte[]> flowInEmitter;

    public ApprovalEventPublisher(ObjectMapper objectMapper,
            @Channel("flow-in-outgoing") Emitter<byte[]> flowInEmitter) {
        this.objectMapper = objectMapper;
        this.flowInEmitter = flowInEmitter;
    }

    public void publishDecision(String workflowInstanceId, ApprovalResponse decision) {
        try {
            byte[] data = objectMapper.writeValueAsBytes(decision);
            flowInEmitter.send(Message.of(data, Metadata.of(cloudEventMetadata(workflowInstanceId))));
            LOG.infof("HITL decision published: workflow=%s approved=%s reviewer=%s",
                    workflowInstanceId, decision.approved(), decision.reviewer());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to publish HITL decision for workflow %s", workflowInstanceId);
            throw new RuntimeException("Failed to send approval CloudEvent", e);
        }
    }

    static OutgoingCloudEventMetadata<Object> cloudEventMetadata(String workflowInstanceId) {
        return OutgoingCloudEventMetadata.builder()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("api:/incidents"))
                .withType(APPROVAL_DONE_TYPE)
                .withDataContentType("application/json")
                .withExtension(INSTANCE_ID_EXTENSION, workflowInstanceId)
                .build();
    }
}
