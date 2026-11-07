package com.skybook.messaging;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the outbox relay and the consumers ({@code skybook.messaging.*}).
 *
 * @param relayBatchSize   outbox rows published per relay transaction
 * @param lockClause       row-locking clause used when claiming outbox rows; {@code FOR UPDATE SKIP LOCKED}
 *                         lets several service replicas relay in parallel without blocking each other
 * @param sendTimeout      how long the relay waits for the broker to acknowledge a record
 * @param retention        how long published outbox rows are kept for diagnostics
 * @param consumerRetries  delivery attempts before a record is sent to the consumer's dead-letter topic
 * @param retryBackoff     initial backoff between attempts (doubled each time)
 */
@ConfigurationProperties("skybook.messaging")
public record MessagingProperties(int relayBatchSize, String lockClause, Duration sendTimeout, Duration retention,
                                  int consumerRetries, Duration retryBackoff) {

    public MessagingProperties {
        relayBatchSize = relayBatchSize <= 0 ? 100 : relayBatchSize;
        lockClause = lockClause == null ? "FOR UPDATE SKIP LOCKED" : lockClause;
        sendTimeout = sendTimeout == null ? Duration.ofSeconds(10) : sendTimeout;
        retention = retention == null ? Duration.ofDays(7) : retention;
        consumerRetries = consumerRetries <= 0 ? 3 : consumerRetries;
        retryBackoff = retryBackoff == null ? Duration.ofMillis(500) : retryBackoff;
    }
}
