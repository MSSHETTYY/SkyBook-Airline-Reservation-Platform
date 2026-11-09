package com.skybook.assistant.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;

import com.skybook.assistant.tools.BookingLookupTools;
import com.skybook.assistant.tools.FlightSearchTools;
import com.skybook.common.security.SkyBookHttpSecurity;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class AssistantConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    FlightSearchTools flightSearchTools(RestClient.Builder builder, AssistantProperties properties) {
        return new FlightSearchTools(client(builder, properties.inventoryBaseUrl(), properties.readTimeout()));
    }

    @Bean
    BookingLookupTools bookingLookupTools(RestClient.Builder builder, AssistantProperties properties) {
        return new BookingLookupTools(client(builder, properties.bookingBaseUrl(), properties.readTimeout()));
    }

    /** Bounded memory: the last N messages per conversation, kept in memory (single replica, ADR-0021). */
    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repository, AssistantProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(properties.memoryMessages())
                .build();
    }

    /**
     * Read-only tools only: nothing the model can call books, pays, cancels or refunds, so a manipulated
     * prompt (prompt injection) cannot change state.
     */
    @Bean
    ChatClient chatClient(ChatClient.Builder builder, ChatMemory memory, FlightSearchTools flights,
                          BookingLookupTools bookings) {
        return builder
                .defaultTools(flights, bookings)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return SkyBookHttpSecurity.apiDefaults(http)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .build();
    }

    private static RestClient client(RestClient.Builder builder, String baseUrl, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
