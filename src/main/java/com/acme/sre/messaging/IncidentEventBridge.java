package com.acme.sre.messaging;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEventData;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.core.data.BytesCloudEventData;
import io.smallrye.reactive.messaging.ce.CloudEventMetadata;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.common.annotation.Blocking;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import com.acme.sre.domain.model.IncidentStatus;

import io.cloudevents.CloudEvent;

/**
 * Consumes the Flow {@code flow-out} topic (CloudEvents emitted by the incident workflow),
 * forwards incident events to connected dashboards via {@link IncidentDashboardSocket}, and
 * projects the workflow's terminal/HITL state back onto the {@link com.acme.sre.domain.model.Incident}
 * record via {@link IncidentProjectionUpdater}.
 */
@ApplicationScoped
public class IncidentEventBridge {

    private static final Logger LOG = Logger.getLogger(IncidentEventBridge.class);

    @Inject
    IncidentProjectionUpdater projectionUpdater;

    @Inject
    ObjectMapper objectMapper;

    @Incoming("flow-out-incoming")
    public CompletionStage<Void> onFlowOut(Message<String> msg) {
        try {
            CloudEvent ce = resolveCloudEvent(msg);

            Object instanceId = ce.getExtension("flowinstanceid");
            byte[] data = ce.getData() != null ? ce.getData().toBytes() : null;
            String payload = (data == null || data.length == 0) ? "null" : new String(data, StandardCharsets.UTF_8);

            String envelope = "{\"type\":\"" + ce.getType()
                    + "\",\"flowInstanceId\":" + jsonString(instanceId)
                    + ",\"data\":" + payload + "}";

            LOG.infof("flow-out -> dashboard: type=%s instance=%s", ce.getType(), instanceId);
            IncidentDashboardSocket.broadcast(envelope);
            projectIncident(ce.getType(), instanceId, data);
        } catch (Exception e) {
            LOG.error("Failed to forward flow-out event to dashboard", e);
        }
        // for demo purpose we ever ack the message
        return msg.ack();
    }

    private void projectIncident(String type, Object instanceId, byte[] data) {
        if (instanceId == null || data == null || data.length == 0) {
            return;
        }
        IncidentStatus status = type.endsWith(".approval.required") ? IncidentStatus.PENDING_APPROVAL
                : type.endsWith(".resolved") ? IncidentStatus.RESOLVED
                : null;
        if (status == null) {
            return;
        }
        try {
            projectionUpdater.apply(instanceId.toString(), status, objectMapper.readTree(data));
        } catch (Exception e) {
            LOG.warnf(e, "Could not project incident state for instance %s", instanceId);
        }
    }

    /**
     * Consumes Flow lifecycle events (workflow/task started/completed, types prefixed
     * {@code io.serverlessworkflow.}) and forwards lightweight progress to the dashboard.
     * Task events carry {@code data.workflow} (instance id) + {@code data.task} (task name);
     * workflow events carry {@code data.name}.
     */
    @Incoming("flow-lifecycle-incoming")
    public CompletionStage<Void> onLifecycle(Message<String> msg) {
        try {
            CloudEvent ce = resolveCloudEvent(msg);

            JsonNode data = objectMapper.readTree(ce.getData().toBytes());

            String instanceId = data.hasNonNull("workflow") ? data.get("workflow").asText()
                    : (data.hasNonNull("name") ? data.get("name").asText() : null);
            if (instanceId == null) {
                return msg.ack();
            }
            String task = data.hasNonNull("task") ? data.get("task").asText() : null;

            String envelope = "{\"kind\":\"lifecycle\",\"type\":" + jsonString(ce.getType())
                    + ",\"flowInstanceId\":" + jsonString(instanceId)
                    + ",\"task\":" + jsonString(task) + "}";
            IncidentDashboardSocket.broadcast(envelope);
        } catch (Exception e) {
            LOG.error("Failed to forward lifecycle event to dashboard", e);
        }

        return msg.ack();
    }

    private static String jsonString(Object value) {
        return value == null ? "null" : "\"" + value.toString().replace("\"", "\\\"") + "\"";
    }

    private static CloudEvent resolveCloudEvent(Message<?> msg) {
        CloudEventMetadata<?> meta = (CloudEventMetadata<?>) msg
                .getMetadata(CloudEventMetadata.class)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Message does not carry CloudEvent metadata. "
                                + "Ensure the channel has cloud-events enabled (the default)."));

        CloudEventBuilder builder = CloudEventBuilder.v1()
                .withId(meta.getId())
                .withSource(meta.getSource())
                .withType(meta.getType());

        meta.getDataContentType().ifPresent(builder::withDataContentType);
        meta.getDataSchema().ifPresent(builder::withDataSchema);
        meta.getSubject().ifPresent(builder::withSubject);
        meta.getTimeStamp()
                .map(ZonedDateTime::toOffsetDateTime)
                .ifPresent(builder::withTime);

        for (Map.Entry<String, Object> ext : meta.getExtensions().entrySet()) {
            Object val = ext.getValue();
            if (val instanceof String s) {
                builder.withExtension(ext.getKey(), s);
            } else if (val instanceof Number n) {
                builder.withExtension(ext.getKey(), n);
            } else if (val instanceof Boolean b) {
                builder.withExtension(ext.getKey(), b);
            } else if (val != null) {
                builder.withExtension(ext.getKey(), val.toString());
            }
        }

        Object data = meta.getData();
        if (data == null) {
            data = msg.getPayload();
        }
        if (data instanceof byte[] bytes) {
            builder.withData(bytes);
        } else if (data instanceof CloudEventData cloudEventData) {
            builder.withData(cloudEventData);
        } else if (data instanceof String text) {
            builder.withData(text.getBytes(StandardCharsets.UTF_8));
        } else if (data instanceof JsonObject jsonObject) {
            builder.withData(jsonObject.encode().getBytes(StandardCharsets.UTF_8));
        } else if (data != null) {
            builder.withData(BytesCloudEventData.wrap(data.toString().getBytes(StandardCharsets.UTF_8)));
        }

        return builder.build();
    }
}
