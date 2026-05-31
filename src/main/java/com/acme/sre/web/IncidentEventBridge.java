package com.acme.sre.web;

import java.nio.charset.StandardCharsets;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;

/**
 * Consumes the Flow {@code flow-out} topic (CloudEvents emitted by the incident workflow)
 * and forwards incident events to connected dashboards via {@link IncidentDashboardSocket}.
 */
@ApplicationScoped
public class IncidentEventBridge {

    private static final Logger LOG = Logger.getLogger(IncidentEventBridge.class);
    private static final JsonFormat CE_JSON = (JsonFormat) EventFormatProvider.getInstance()
            .resolveFormat(JsonFormat.CONTENT_TYPE);
    private static final String INCIDENT_EVENT_PREFIX = "com.acme.sre.incident.";

    @Incoming("flow-out-incoming")
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
        } catch (Exception e) {
            LOG.error("Failed to forward flow-out event to dashboard", e);
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
