package com.example.movieticket.config;

import com.example.movieticket.security.StompAuthChannelInterceptor;
import com.example.movieticket.security.WebSocketSessionRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/**
 * Module 6's transport wiring (plan/websockets.md section 3.1): STOMP over
 * WebSocket, one endpoint ({@code /ws}), a simple in-memory broker. No
 * {@code @MessageMapping} anywhere in this app - the socket is push-only (section
 * 3.3); locking a seat stays {@code POST /shows/{showId}/seats/lock}.
 *
 * <p><b>Rejected: {@code .withSockJS()}.</b> Every browser this project targets
 * has had native WebSocket for a decade; SockJS's fallback surface and extra
 * endpoints buy nothing here (section 3.1).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // CORS on the handshake (section 8.5) - Module 8's frontend is the consumer.
    // setAllowedOriginPatterns, NOT setAllowedOrigins("*"): the latter is rejected
    // outright once credentials are in play, with a failure message that points at
    // CORS rather than at the wildcard.
    @Value("${websocket.allowed-origins}")
    private String[] allowedOrigins;

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final WebSocketSessionRegistry sessionRegistry;
    private final TaskScheduler websocketTaskScheduler;

    // websocketTaskScheduler is sourced from WebSocketSchedulerConfig, not a
    // @Bean method here - see that class's javadoc. Defining it here instead
    // would create a circular dependency: this class needs
    // StompAuthChannelInterceptor (constructor injection), which itself needs a
    // TaskScheduler, which a @Bean method on THIS class couldn't produce until
    // this class already exists.
    public WebSocketConfig(StompAuthChannelInterceptor stompAuthChannelInterceptor,
                            WebSocketSessionRegistry sessionRegistry,
                            TaskScheduler websocketTaskScheduler) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
        this.sessionRegistry = sessionRegistry;
        this.websocketTaskScheduler = websocketTaskScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // /topic for the public seat-status fan-out, /queue for the per-user
        // booking push (section 3.1's destination table).
        registry.enableSimpleBroker("/topic", "/queue").setTaskScheduler(websocketTaskScheduler);
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // JWT-on-CONNECT + the /user/** SUBSCRIBE guard (section 8.2/8.3) - the
        // one place this module's authentication/authorization actually lives.
        // Deliberately NOT @EnableWebSocketSecurity (section 8.4).
        registration.interceptors(stompAuthChannelInterceptor);
    }

    /**
     * Decorates the handler behind {@code /ws} purely to observe connect/close and
     * hand each session to {@link WebSocketSessionRegistry} - see that class's
     * javadoc for why this indirection is necessary at all (Spring's STOMP stack
     * has no other way to force-close one specific session from application code).
     * This decorator does not touch frames; it only tracks session lifecycle.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(new WebSocketHandlerDecoratorFactory() {
            @Override
            public WebSocketHandler decorate(WebSocketHandler handler) {
                return new WebSocketHandlerDecorator(handler) {
                    @Override
                    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                        sessionRegistry.register(session);
                        super.afterConnectionEstablished(session);
                    }

                    @Override
                    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                        sessionRegistry.unregister(session);
                        super.afterConnectionClosed(session, closeStatus);
                    }
                };
            }
        });
    }
}
