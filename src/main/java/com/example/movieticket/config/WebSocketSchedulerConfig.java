package com.example.movieticket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Produces the {@link TaskScheduler} Module 6 needs (plan/websockets.md section
 * 11-A: scheduling a WebSocket session's force-close at the instant its
 * authenticating access token expires), used by both
 * {@code StompAuthChannelInterceptor} (schedules the close) and
 * {@code WebSocketConfig} (wires the same instance into the simple broker for
 * heartbeats).
 *
 * <p>Deliberately its own tiny {@code @Configuration} class with zero
 * dependencies, rather than a {@code @Bean} method on {@code WebSocketConfig}
 * itself: {@code WebSocketConfig}'s constructor needs
 * {@code StompAuthChannelInterceptor}, which needs this {@code TaskScheduler} -
 * a {@code @Bean} method living on {@code WebSocketConfig} couldn't run until
 * {@code WebSocketConfig} itself was already constructed, which is a circular
 * dependency. Splitting the source of this one bean out breaks the cycle
 * without resorting to {@code @Lazy} injection tricks.
 */
@Configuration
public class WebSocketSchedulerConfig {

    @Bean
    public TaskScheduler websocketTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-session-cap-");
        scheduler.initialize();
        return scheduler;
    }
}
