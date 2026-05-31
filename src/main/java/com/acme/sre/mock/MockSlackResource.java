package com.acme.sre.mock;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

@Path("/mock/slack")
public class MockSlackResource {

    private static final Logger LOG = Logger.getLogger(MockSlackResource.class);
    private final Map<String, NotificationRecord> notificationLog = new ConcurrentHashMap<>();

    @POST
    @Path("/notify")
    public Response sendNotification(Map<String, Object> payload) {
        String notificationId = UUID.randomUUID().toString();
        String channel = (String) payload.getOrDefault("channel", "#sre-alerts");
        String message = (String) payload.getOrDefault("message", "no message");

        LOG.infof("Mock Slack webhook: posting to %s - %s", channel, message);

        notificationLog.put(notificationId, new NotificationRecord(notificationId, channel, message, Instant.now()));

        return Response.ok(Map.of(
                "notificationId", notificationId,
                "status", "SENT",
                "channel", channel,
                "timestamp", Instant.now().toString())).build();
    }

    public Map<String, NotificationRecord> getNotificationLog() {
        return Map.copyOf(notificationLog);
    }

    public record NotificationRecord(String notificationId, String channel, String message, Instant sentAt) {
    }
}
