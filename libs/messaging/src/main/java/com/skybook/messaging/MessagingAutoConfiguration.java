package com.skybook.messaging;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

/**
 * Outbox, idempotent consumers and dead-letter handling for services that include this library.
 * Boot's Kafka auto-configuration applies the {@link CommonErrorHandler} bean to the default listener
 * container factory, so every {@code @KafkaListener} gets retries and a dead-letter topic.
 */
@AutoConfiguration
@EnableConfigurationProperties(MessagingProperties.class)
@ConditionalOnProperty(name = "skybook.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    EventCodec eventCodec(ObjectMapper objectMapper) {
        return new EventCodec(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxWriter outboxWriter(JdbcTemplate jdbc, EventCodec codec, Clock clock) {
        return new OutboxWriter(jdbc, codec, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxRelay outboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                            MessagingProperties properties, Clock clock) {
        return new OutboxRelay(jdbc, kafka, tx, properties, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessedEventStore processedEventStore(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        return new ProcessedEventStore(jdbc, tx, clock);
    }

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    CommonErrorHandler deadLetterErrorHandler(KafkaTemplate<String, String> kafka, MessagingProperties properties,
                                              @Value("${spring.kafka.consumer.group-id:${spring.application.name}}")
                                              String consumerGroup) {
        return DeadLetterSupport.errorHandler(kafka, consumerGroup, properties);
    }
}
