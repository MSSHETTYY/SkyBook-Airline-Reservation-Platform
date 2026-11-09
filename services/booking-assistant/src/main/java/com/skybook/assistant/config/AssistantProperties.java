package com.skybook.assistant.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param inventoryBaseUrl  inventory-service (public flight search, API v2)
 * @param bookingBaseUrl    booking-service (the caller's own bookings, with the caller's token)
 * @param readTimeout       per tool call; the model waits for tool results, so keep it short
 * @param memoryMessages    messages kept per conversation (context window and cost bound)
 */
@ConfigurationProperties("skybook.assistant")
public record AssistantProperties(String inventoryBaseUrl, String bookingBaseUrl, Duration readTimeout,
                                  int memoryMessages) {

    public AssistantProperties {
        readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
        memoryMessages = memoryMessages <= 0 ? 12 : memoryMessages;
    }
}
