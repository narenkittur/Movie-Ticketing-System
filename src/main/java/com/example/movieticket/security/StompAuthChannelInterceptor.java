package com.example.movieticket.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Authenticates the STOMP {@code CONNECT} frame and guards {@code SUBSCRIBE}
 * (plan/websockets.md sections 8.2/8.3). Registered via
 * {@code configureClientInboundChannel} in {@code WebSocketConfig}.
 *
 * <p><b>Why this exists at all, not JwtAuthenticationFilter:</b> a browser's
 * {@code WebSocket} constructor cannot set request headers, so there is no
 * {@code Authorization} header on the HTTP handshake for the servlet filter chain
 * to see. The token instead rides as a STOMP <i>native</i> header on the CONNECT
 * frame - application data the client fully controls, read here in
 * {@code preSend} rather than at the handshake. See plan/websockets.md section
 * 8.2's rejected alternative (a {@code ?token=} query parameter) for why it isn't
 * on the URL instead: that would land in access logs and browser history.
 *
 * <p><b>Anonymous CONNECT is allowed</b> - {@code GET /shows/**} is already
 * {@code permitAll}, so the seat-map broadcast on {@code /topic/shows/**} is the
 * same public information delivered a different way (section 8.3). A token that
 * IS presented must still be valid; a rejected token refuses the CONNECT rather
 * than silently downgrading to anonymous, since that would hide a bug or an
 * attack. {@code /user/**} SUBSCRIBEs are the one thing that stays gated on an
 * actual principal, since that queue carries a real user's booking data.
 *
 * <p><b>Open Decision A (plan/websockets.md section 11-A), resolved yes:</b> a
 * successfully authenticated CONNECT schedules a force-close of this exact
 * session at the moment its access token would have expired anyway, via
 * {@link WebSocketSessionRegistry}. Without this, a session authenticated at
 * minute 0 stays authenticated for as long as the socket stays open - hours,
 * days - regardless of the 30-minute access token that authorised it. The client
 * is expected to reconnect with a freshly refreshed token; it already has that
 * machinery from Module 2's refresh flow.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String USER_DESTINATION_PREFIX = "/user/";

    private final JwtService jwtService;
    private final WebSocketSessionRegistry sessionRegistry;
    private final TaskScheduler taskScheduler;

    public StompAuthChannelInterceptor(JwtService jwtService, WebSocketSessionRegistry sessionRegistry,
                                        TaskScheduler taskScheduler) {
        this.jwtService = jwtService;
        this.sessionRegistry = sessionRegistry;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticateConnect(message, accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            guardUserDestinationSubscribe(message, accessor);
        }

        return message;
    }

    /**
     * Do NOT add {@code @EnableWebSocketSecurity} instead of hand-rolling this -
     * it pulls in STOMP CSRF, which this app cannot satisfy since
     * {@code SecurityConfig} disables CSRF entirely (correctly: pure bearer-token
     * auth, no cookies). See plan/websockets.md section 8.4.
     */
    private void authenticateConnect(Message<?> message, StompHeaderAccessor accessor) {
        String bearer = accessor.getFirstNativeHeader("Authorization");
        if (bearer == null) {
            log.debug("Anonymous CONNECT on session {} (no Authorization header)", accessor.getSessionId());
            return; // Anonymous is allowed - section 8.3.
        }

        if (!bearer.startsWith(BEARER_PREFIX)) {
            throw new MessagingException(message, "Malformed Authorization header on CONNECT");
        }
        String jwt = bearer.substring(BEARER_PREFIX.length());

        if (!jwtService.isAccessTokenValid(jwt)) {
            // A token that IS presented but is invalid/expired is refused outright -
            // never silently downgraded to anonymous (section 8.3). A rejected
            // CONNECT is the correct, loud failure mode here.
            log.warn("Rejected CONNECT on session {}: invalid or expired access token", accessor.getSessionId());
            throw new MessagingException(message, "Invalid or expired access token");
        }

        String username = jwtService.extractUsername(jwt);
        String role = jwtService.extractRole(jwt);
        var authToken = new UsernamePasswordAuthenticationToken(username, null, List.of(new SimpleGrantedAuthority(role)));
        accessor.setUser(authToken);
        log.info("Authenticated WebSocket session {} for user '{}' (role={})", accessor.getSessionId(), username, role);

        scheduleSessionCap(accessor.getSessionId(), jwt);
    }

    /** Open Decision A - see class javadoc. */
    private void scheduleSessionCap(String sessionId, String jwt) {
        Instant expiresAt = jwtService.extractExpiration(jwt);
        taskScheduler.schedule(() -> sessionRegistry.closeSession(sessionId), expiresAt);
        log.debug("Scheduled force-close of session {} at {}", sessionId, expiresAt);
    }

    /**
     * {@code /topic/**} stays open to anonymous subscribers (section 8.3) -
     * Spring's user-destination resolution already routes a principal-less
     * SUBSCRIBE for {@code /user/**} to nowhere, but "no principal so nobody gets
     * the message" is an accident that happens to be safe, not a check. Make it
     * an explicit, loud refusal instead.
     */
    private void guardUserDestinationSubscribe(Message<?> message, StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination != null && destination.startsWith(USER_DESTINATION_PREFIX) && accessor.getUser() == null) {
            log.warn("Rejected anonymous SUBSCRIBE to {} on session {}", destination, accessor.getSessionId());
            throw new MessagingException(message, "Authentication required to subscribe to " + destination);
        }
    }
}
