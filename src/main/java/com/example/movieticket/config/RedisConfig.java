package com.example.movieticket.config;

import com.example.movieticket.service.SeatLockExpiryListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.List;

/**
 * The project's Redis {@code @Bean}s. The two Lua scripts are Module 4
 * (plan/redis.md section 5.3): loaded from the classpath once at startup and
 * registered with Redis by SHA the first time they run;
 * {@code RedisTemplate.execute(script, ...)} then sends {@code EVALSHA} and only
 * falls back to shipping the whole script body if Redis doesn't know that SHA yet
 * - so the script text crosses the wire roughly once per Redis restart, not once
 * per lock/release call.
 *
 * {@code StringRedisTemplate} needs no {@code @Bean} here - Spring Boot
 * auto-configures it from the {@code spring.data.redis.*} properties already in
 * {@code application.properties}.
 */
@Configuration
public class RedisConfig {

    @Bean
    public DefaultRedisScript<List> lockSeatsScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/lock_seats.lua"));
        script.setResultType(List.class);
        return script;
    }

    @Bean
    public DefaultRedisScript<List> unlockSeatsScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/unlock_seats.lua"));
        script.setResultType(List.class);
        return script;
    }

    /**
     * Module 6 (plan/websockets.md section 5.1): subscribes
     * {@link SeatLockExpiryListener} to {@code __keyevent@0__:expired}, the
     * channel Redis publishes an expired key's name on when keyspace
     * notifications are enabled ({@code docker-compose.yml}'s
     * {@code --notify-keyspace-events Kx}). Database index 0 is hardcoded to match
     * this project's Redis config, which never sets {@code spring.data.redis.database}
     * (Spring Boot's own default is 0) - a deployment using a different logical DB
     * would need this pattern updated too.
     *
     * <p>{@code @ConditionalOnProperty} mirrors {@link SeatLockExpiryListener}'s own
     * gate: if the listener bean doesn't exist (property off), this container would
     * otherwise fail to start with an unsatisfied dependency, rather than simply
     * not existing.
     */
    @Bean
    @ConditionalOnProperty(name = "websocket.expiry-notifications.enabled", havingValue = "true", matchIfMissing = true)
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory,
                                                                        SeatLockExpiryListener seatLockExpiryListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(seatLockExpiryListener, new PatternTopic("__keyevent@0__:expired"));
        return container;
    }
}
