package com.skybook.notification.config;

import jakarta.jms.ConnectionFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import com.skybook.common.security.SkyBookHttpSecurity;
import com.solacesystems.jms.SolConnectionFactory;
import com.solacesystems.jms.SolJmsUtility;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class NotificationConfig {

    @Bean
    Retry mailRetry(RetryRegistry retries) {
        return retries.retry("mail");
    }

    /**
     * Transacted sessions: a message is removed from the queue only if processing completed.
     *
     * <p>One consumer: {@code q.notification} is an exclusive queue (one active consumer, others on standby),
     * which keeps per-queue order and means a duplicate delivery is always processed after the original, so
     * the audit-based de-duplication cannot race with itself. Throughput scales by partitioning queues
     * (e.g. by event type), not by concurrent consumers on one queue.
     */
    @Bean
    DefaultJmsListenerContainerFactory notificationListenerFactory(ConnectionFactory connectionFactory) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setSessionTransacted(true);
        factory.setPubSubDomain(false);
        factory.setConcurrency("1");
        return factory;
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return SkyBookHttpSecurity.apiDefaults(http)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .build();
    }

    /** Solace PubSub+ through the standard JMS API; tests use an embedded broker instead. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "skybook.solace.enabled", havingValue = "true", matchIfMissing = true)
    static class Solace {

        @Bean
        ConnectionFactory connectionFactory(@Value("${skybook.solace.host}") String host,
                                            @Value("${skybook.solace.vpn}") String vpn,
                                            @Value("${skybook.solace.username}") String username,
                                            @Value("${skybook.solace.password}") String password) throws Exception {
            SolConnectionFactory solace = SolJmsUtility.createConnectionFactory();
            solace.setHost(host);
            solace.setVPN(vpn);
            solace.setUsername(username);
            solace.setPassword(password);
            solace.setReconnectRetries(-1);
            return new CachingConnectionFactory(solace);
        }
    }
}
