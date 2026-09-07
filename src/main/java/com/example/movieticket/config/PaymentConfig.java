package com.example.movieticket.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Module 5 config (plan/payment.md sections 6.3, 9): binds {@link PaymentProperties}
 * and gives the payment gateway its own {@code RestClient} with EXPLICIT
 * connect/read timeouts - never the JDK/Spring defaults (which are effectively
 * unbounded). Holding a pooled resource open indefinitely for a slow third party
 * is the exact failure shape claude.md already warns about for Lettuce's 60s
 * default (plan/payment.md section 5.1) - this is that same lesson one layer out,
 * for outbound HTTPS instead of Redis.
 */
@Configuration
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfig {

    @Bean
    public RestClient paymentGatewayRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
