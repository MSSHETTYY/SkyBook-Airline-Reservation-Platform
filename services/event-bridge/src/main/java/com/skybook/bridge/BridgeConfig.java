package com.skybook.bridge;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;

import com.skybook.messaging.DeadLetterSupport;
import com.skybook.messaging.EventCodec;
import com.skybook.messaging.MessagingProperties;
import com.solacesystems.jms.SolConnectionFactory;
import com.solacesystems.jms.SolJmsUtility;

import tools.jackson.databind.ObjectMapper;

/**
 * The bridge has no database, so the outbox parts of the messaging library are switched off
 * ({@code skybook.messaging.enabled=false}) and only its codec and dead-letter handling are used.
 */
@Configuration(proxyBeanMethods = false)
class BridgeConfig {

    @Bean
    EventCodec eventCodec(ObjectMapper objectMapper) {
        return new EventCodec(objectMapper);
    }

    @Bean
    CommonErrorHandler deadLetterErrorHandler(KafkaTemplate<String, String> kafka) {
        return DeadLetterSupport.errorHandler(kafka, EventForwarder.GROUP,
                new MessagingProperties(0, null, null, null, 5, null));
    }

    @Bean
    NewTopic bookingEventsDeadLetters(@Value("${skybook.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(DeadLetterSupport.deadLetterTopic("skybook.booking.events", EventForwarder.GROUP))
                .partitions(3).replicas(replicas).build();
    }

    @Bean
    NewTopic paymentEventsDeadLetters(@Value("${skybook.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(DeadLetterSupport.deadLetterTopic("skybook.payment.events", EventForwarder.GROUP))
                .partitions(3).replicas(replicas).build();
    }

    /** Publishes to topics with persistent (guaranteed) delivery. */
    @Bean
    JmsTemplate jmsTemplate(ConnectionFactory connectionFactory) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setPubSubDomain(true);
        template.setExplicitQosEnabled(true);
        template.setDeliveryMode(DeliveryMode.PERSISTENT);
        return template;
    }

    /** Solace PubSub+ through the standard JMS API (switched off in tests, which use an embedded broker). */
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
            solace.setDirectTransport(false);
            return new CachingConnectionFactory(solace);
        }
    }
}
