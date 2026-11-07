package com.skybook.messaging;

import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Consumer error handling: retry transient failures with exponential backoff, then park the record on
 * a dead-letter topic named {@code <topic>.<consumer-group>.DLT} (same partition) so one bad record
 * never blocks its partition. Records that can never succeed ({@link EventDecodingException},
 * {@link IllegalArgumentException}) skip the retries.
 *
 * <p>The DLT name includes the consumer group because several services consume the same topic: an
 * operator must know <em>which</em> consumer failed before replaying a record.
 */
public final class DeadLetterSupport {

    private DeadLetterSupport() {
    }

    public static String deadLetterTopic(String topic, String consumerGroup) {
        return topic + "." + consumerGroup + ".DLT";
    }

    public static DefaultErrorHandler errorHandler(KafkaOperations<?, ?> kafka, String consumerGroup,
                                                   MessagingProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(deadLetterTopic(record.topic(), consumerGroup),
                        record.partition()));
        ExponentialBackOff backOff = new ExponentialBackOff(properties.retryBackoff().toMillis(), 2.0);
        backOff.setMaxAttempts(properties.consumerRetries() - 1);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(EventDecodingException.class, IllegalArgumentException.class);
        return handler;
    }
}
