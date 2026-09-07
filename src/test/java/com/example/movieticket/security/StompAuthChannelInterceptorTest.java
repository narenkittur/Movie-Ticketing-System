package com.example.movieticket.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StompAuthChannelInterceptor (plan/websockets.md section 10, tests
 * 5-6): CONNECT authentication (valid/invalid/absent token) and the /user/**
 * SUBSCRIBE guard.
 */
@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    @Mock private JwtService jwtService;
    @Mock private WebSocketSessionRegistry sessionRegistry;
    @Mock private TaskScheduler taskScheduler;
    @Mock private MessageChannel channel;

    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthChannelInterceptor(jwtService, sessionRegistry, taskScheduler);
    }

    private Message<byte[]> connectMessage(String bearerHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("session-1");
        if (bearerHeader != null) {
            accessor.setNativeHeader("Authorization", bearerHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> subscribeMessage(String destination, boolean authenticated) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        if (authenticated) {
            accessor.setUser(new UsernamePasswordAuthenticationToken("alice", null));
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private StompHeaderAccessor accessorOf(Message<byte[]> message) {
        return org.springframework.messaging.support.MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    }

    @Test
    void connect_withNoAuthorizationHeader_connectsAnonymously() {
        Message<byte[]> message = connectMessage(null);

        assertDoesNotThrow(() -> interceptor.preSend(message, channel));

        assertNull(accessorOf(message).getUser());
        verifyNoInteractions(taskScheduler);
    }

    @Test
    void connect_withValidToken_setsThePrincipal_andSchedulesTheSessionCap() {
        when(jwtService.isAccessTokenValid("good-token")).thenReturn(true);
        when(jwtService.extractUsername("good-token")).thenReturn("alice");
        when(jwtService.extractRole("good-token")).thenReturn("ROLE_USER");
        when(jwtService.extractExpiration("good-token")).thenReturn(Instant.now().plusSeconds(1800));

        Message<byte[]> message = connectMessage("Bearer good-token");
        interceptor.preSend(message, channel);

        assertEquals("alice", accessorOf(message).getUser().getName());
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void connect_withInvalidToken_refusesTheConnect() {
        when(jwtService.isAccessTokenValid("bad-token")).thenReturn(false);

        Message<byte[]> message = connectMessage("Bearer bad-token");

        assertThrows(MessagingException.class, () -> interceptor.preSend(message, channel));
        verifyNoInteractions(taskScheduler);
    }

    @Test
    void connect_withMalformedAuthorizationHeader_refusesTheConnect() {
        Message<byte[]> message = connectMessage("not-a-bearer-token");

        assertThrows(MessagingException.class, () -> interceptor.preSend(message, channel));
    }

    @Test
    void subscribe_toUserQueue_withNoPrincipal_isRejected() {
        Message<byte[]> message = subscribeMessage("/user/queue/bookings", false);

        assertThrows(MessagingException.class, () -> interceptor.preSend(message, channel));
    }

    @Test
    void subscribe_toShowTopic_withNoPrincipal_isPermitted() {
        Message<byte[]> message = subscribeMessage("/topic/shows/12/seats", false);

        assertDoesNotThrow(() -> interceptor.preSend(message, channel));
    }

    @Test
    void subscribe_toUserQueue_withPrincipal_isPermitted() {
        Message<byte[]> message = subscribeMessage("/user/queue/bookings", true);

        assertDoesNotThrow(() -> interceptor.preSend(message, channel));
    }
}
