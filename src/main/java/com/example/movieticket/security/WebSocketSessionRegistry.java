package com.example.movieticket.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks live {@code /ws} sessions by STOMP session id, so {@link
 * StompAuthChannelInterceptor} can force-close one later (Open Decision A,
 * plan/websockets.md section 11-A) - capping a socket's lifetime to the access
 * token that authenticated it.
 *
 * <p>Spring's STOMP stack has no built-in "close this specific session from
 * anywhere in the app" API: {@code SimpMessagingTemplate} only ever sends
 * messages, and the raw {@link WebSocketSession} is otherwise buried inside the
 * internal {@code SubProtocolWebSocketHandler}. {@code WebSocketConfig} exposes it
 * here instead, by decorating the handler registered for {@code /ws}
 * ({@code registerWebSocketTransport}) purely to observe connect/close and record
 * the session - this class does not participate in the STOMP frame pipeline at
 * all, it is a lookup table.
 */
@Component
public class WebSocketSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionRegistry.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** Called by {@code WebSocketConfig}'s handler decorator on connect - package-crossing, so public. */
    public void register(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    /** Called by {@code WebSocketConfig}'s handler decorator on close - package-crossing, so public. */
    public void unregister(WebSocketSession session) {
        sessions.remove(session.getId());
    }

    /**
     * Closes the given session with {@link CloseStatus#POLICY_VIOLATION} if it's
     * still open and still tracked. A no-op if the session already disconnected on
     * its own between being scheduled and now - not an error, just a stale
     * scheduled task finding nothing left to do.
     */
    public void closeSession(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            log.info("Force-closing WebSocket session {} - authenticating access token has expired", sessionId);
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Access token expired"));
        } catch (IOException ex) {
            log.warn("Failed to close expired WebSocket session {}: {}", sessionId, ex.getMessage());
        }
    }
}
