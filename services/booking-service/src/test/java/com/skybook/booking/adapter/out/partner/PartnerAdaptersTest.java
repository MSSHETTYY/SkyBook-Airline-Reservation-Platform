package com.skybook.booking.adapter.out.partner;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.skybook.booking.adapter.out.support.RemoteCall;
import com.skybook.booking.domain.model.CustomerTier;
import com.skybook.booking.domain.model.FlightSnapshot;
import com.skybook.booking.domain.model.Passenger;
import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.money.Money;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import tools.jackson.databind.json.JsonMapper;

/**
 * The partner adapters against the <b>real</b> WireMock mappings in {@code mock-partners/wiremock}, so the
 * mappings the local stack uses are verified by the build too.
 */
class PartnerAdaptersTest {

    private static final FlightSnapshot FLIGHT = new FlightSnapshot(7L, "SB101", "DXB", "LHR",
            Instant.parse("2026-12-11T08:00:00Z"));
    private static final List<Passenger> PASSENGERS = List.of(
            new Passenger("Ada", "Lovelace", LocalDate.of(1990, 1, 1), "12A", Money.of("100", "USD"), null),
            new Passenger("Alan", "Turing", LocalDate.of(1991, 1, 1), "12B", Money.of("100", "USD"), null));

    private static WireMockServer partners;

    @BeforeAll
    static void startPartnersFromTheRepositoryMappings() {
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", "../.."))
                .resolve("mock-partners/wiremock");
        if (!root.toFile().isDirectory()) {
            root = Path.of("../../mock-partners/wiremock");
        }
        partners = new WireMockServer(WireMockConfiguration.options().dynamicPort()
                .usingFilesUnderDirectory(root.toString()));
        partners.start();
    }

    @AfterAll
    static void stop() {
        partners.stop();
    }

    private static RestClient client() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
        factory.setReadTimeout(Duration.ofMillis(1500));
        return RestClient.builder().baseUrl(partners.baseUrl()).requestFactory(factory).build();
    }

    private static RemoteCall remote(String name) {
        return new RemoteCall(name, CircuitBreaker.ofDefaults(name),
                Retry.of(name, RetryConfig.custom().maxAttempts(2).waitDuration(Duration.ofMillis(5))
                        .retryExceptions(RemoteCall.Unavailable.class).build()),
                Bulkhead.ofDefaults(name), JsonMapper.builder().build());
    }

    @Test
    void gdsIssuesOneTicketPerPassengerWithAnIdempotencyKey() {
        GdsTicketingAdapter gds = new GdsTicketingAdapter(client(), remote("gds"));

        List<String> tickets = gds.issueTickets("K7QX2M", FLIGHT, PASSENGERS);

        assertThat(tickets).hasSize(2).allMatch(ticket -> ticket.matches("176\\d{10}"));
        partners.verify(postRequestedFor(urlPathEqualTo("/gds/v1/tickets"))
                .withHeader("Idempotency-Key", equalTo("K7QX2M-7")));
        assertThatCode(() -> gds.voidTickets("K7QX2M", tickets)).doesNotThrowAnyException();
        assertThatCode(() -> gds.voidTickets("K7QX2M", List.of())).doesNotThrowAnyException();
    }

    @Test
    void gdsFaultInjectionFlightsFailOrTimeOut() {
        GdsTicketingAdapter gds = new GdsTicketingAdapter(client(), remote("gds-faults"));

        assertThatThrownBy(() -> gds.issueTickets("K7QX2M", new FlightSnapshot(9L, "SB999", "DXB", "LHR",
                FLIGHT.departureTime()), PASSENGERS))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.SERVICE_UNAVAILABLE);
        assertThatThrownBy(() -> gds.issueTickets("K7QX2M", new FlightSnapshot(9L, "SB998", "DXB", "LHR",
                FLIGHT.departureTime()), PASSENGERS.subList(0, 1)))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void crmReturnsTiersAndFallsBackToStandard() {
        CrmCustomerProfileAdapter crm = new CrmCustomerProfileAdapter(client(), remote("crm"));

        assertThat(crm.tierOf("alice-gold")).isEqualTo(CustomerTier.GOLD);
        assertThat(crm.tierOf("bob")).isEqualTo(CustomerTier.STANDARD);

        partners.stubFor(get(urlPathMatching("/crm/v1/customers/broken.*")).atPriority(1)
                .willReturn(aResponse().withStatus(500)));
        assertThat(crm.tierOf("broken-gold")).as("outage falls back to STANDARD").isEqualTo(CustomerTier.STANDARD);
    }

    @Test
    void loyaltyAccrualIsBestEffort() {
        LoyaltyRestAdapter loyalty = new LoyaltyRestAdapter(client(), remote("loyalty"));
        assertThatCode(() -> loyalty.accrueMiles("cust-1", "K7QX2M", Money.of("200", "USD")))
                .doesNotThrowAnyException();

        LoyaltyRestAdapter unreachable = new LoyaltyRestAdapter(
                RestClient.builder().baseUrl("http://localhost:1").build(), remote("loyalty-down"));
        assertThatCode(() -> unreachable.accrueMiles("cust-1", "K7QX2M", Money.of("200", "USD")))
                .doesNotThrowAnyException();
    }
}
