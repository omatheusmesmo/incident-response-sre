package com.acme.sre.messaging;

import java.nio.charset.StandardCharsets;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.common.annotation.Blocking;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import com.acme.sre.domain.IncidentStatus;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;

/**
 * Consumes the Flow {@code flow-out} topic (CloudEvents emitted by the incident workflow),
 * forwards incident events to connected dashboards via {@link IncidentDashboardSocket}, and
 * projects the workflow's terminal/HITL state back onto the {@link com.acme.sre.domain.Incident}
 * record via {@link IncidentProjectionUpdater}.
 */
@ApplicationScoped
public class IncidentEventBridge {

    private static final Logger LOG = Logger.getLogger(IncidentEventBridge.class);
    private static final JsonFormat CE_JSON = (JsonFormat) EventFormatProvider.getInstance()
            .resolveFormat(JsonFormat.CONTENT_TYPE);
    private static final String INCIDENT_EVENT_PREFIX = "com.acme.sre.incident.";

    @Inject
    IncidentProjectionUpdater projectionUpdater;

    @Incoming("flow-out-incoming")
    @Blocking
    public void onFlowOut(byte[] record) {
        try {
            CloudEvent ce = CE_JSON.deserialize(record);
            if (ce == null || ce.getType() == null || !ce.getType().startsWith(INCIDENT_EVENT_PREFIX)) {
                return;
            }

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
            projectionUpdater.apply(instanceId.toString(), status, MAPPER.readTree(data));
        } catch (Exception e) {
            LOG.warnf(e, "Could not project incident state for instance %s", instanceId);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Consumes Flow lifecycle events (workflow/task started/completed, types prefixed
     * {@code io.serverlessworkflow.}) and forwards lightweight progress to the dashboard.
     * Task events carry {@code data.workflow} (instance id) + {@code data.task} (task name);
     * workflow events carry {@code data.name}.
     */
    @Incoming("flow-lifecycle-incoming")
    public void onLifecycle(byte[] record) {
        try {
            CloudEvent ce = CE_JSON.deserialize(record);
            if (ce == null || ce.getType() == null || !ce.getType().startsWith("io.serverlessworkflow.")
                    || ce.getData() == null) {
                return;
            }
            com.fasterxml.jackson.databind.JsonNode data = MAPPER.readTree(ce.getData().toBytes());
            String instanceId = data.hasNonNull("workflow") ? data.get("workflow").asText()
                    : (data.hasNonNull("name") ? data.get("name").asText() : null);
            if (instanceId == null) {
                return;
            }
            String task = data.hasNonNull("task") ? data.get("task").asText() : null;

            String envelope = "{\"kind\":\"lifecycle\",\"type\":" + jsonString(ce.getType())
                    + ",\"flowInstanceId\":" + jsonString(instanceId)
                    + ",\"task\":" + jsonString(task) + "}";
            IncidentDashboardSocket.broadcast(envelope);
        } catch (Exception e) {
            LOG.error("Failed to forward lifecycle event to dashboard", e);
        }
    }

    private static String jsonString(Object value) {
        return value == null ? "null" : "\"" + value.toString().replace("\"", "\\\"") + "\"";
    }
}
