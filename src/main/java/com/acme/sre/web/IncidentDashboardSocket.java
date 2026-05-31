package com.acme.sre.web;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import org.jboss.logging.Logger;

/**
 * Broadcasts incident workflow events (already JSON) to every connected browser.
 * Fed by {@link IncidentEventBridge}, which consumes the Flow {@code flow-out} topic.
 */
@ServerEndpoint("/ws/incidents")
@ApplicationScoped
public class IncidentDashboardSocket {

    private static final Logger LOG = Logger.getLogger(IncidentDashboardSocket.class);
    private static final Set<Session> SESSIONS = ConcurrentHashMap.newKeySet();

    @OnOpen
    public void onOpen(Session session) {
        SESSIONS.add(session);
        LOG.infof("Dashboard client connected (%d total)", SESSIONS.size());
    }

    @OnClose
    public void onClose(Session session) {
        SESSIONS.remove(session);
    }

    @OnError
    public void onError(Session session, Throwable t) {
        SESSIONS.remove(session);
        LOG.error("Dashboard websocket error", t);
    }

    public static void broadcast(String json) {
        for (Session s : SESSIONS) {
            if (s.isOpen()) {
                try {
                    s.getBasicRemote().sendText(json);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
