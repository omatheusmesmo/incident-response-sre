package com.acme.sre.messaging;

import java.net.URI;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import com.acme.sre.domain.contract.ApprovalResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;

/**
 * Publishes the SRE approve/reject decision as a CloudEvent on {@code flow-in}, correlated to the
 * paused workflow instance via the {@code flowinstanceid} extension. This is what resumes a Layer 3
 * workflow waiting at its HITL {@code listen} gate. Owning the CloudEvent plumbing here keeps the
 * REST resource free of serialization concerns.
 */
@ApplicationScoped
public class ApprovalEventPublisher {

    private static final Logger LOG = Logger.getLogger(ApprovalEventPublisher.class);
    private static final String APPROVAL_DONE_TYPE = "com.acme.sre.incident.approval.done";

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
            CloudEvent event = CloudEventBuilder.v1()
                    .withId(UUID.randomUUID().toString())
                    .withExtension("flowinstanceid", workflowInstanceId)
                    .withSource(URI.create("api:/incidents"))
                    .withType(APPROVAL_DONE_TYPE)
                    .withDataContentType("application/json")
                    .withData(data)
                    .build();

            flowInEmitter.send(new JsonFormat().serialize(event));
            LOG.infof("HITL decision published: workflow=%s approved=%s reviewer=%s",
                    workflowInstanceId, decision.approved(), decision.reviewer());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to publish HITL decision for workflow %s", workflowInstanceId);
            throw new RuntimeException("Failed to send approval CloudEvent", e);
        }
    }
}
