package com.skybook.booking.adapter.in.messaging;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;

import com.skybook.booking.adapter.out.messaging.OutboxBookingEventPublisher;
import com.skybook.booking.support.BookingTestSupport;
import com.skybook.booking.support.InventoryStubs;
import com.skybook.common.event.EventEnvelope;
import com.skybook.common.security.Roles;
import com.skybook.messaging.EventCodec;
import com.skybook.messaging.OutboxRelay;

import io.restassured.http.ContentType;

/**
 * The saga end to end over a real (embedded) Kafka broker: PaymentCompleted from payment-service drives
 * ticketing and confirmation; the booking's own events leave through the outbox; redelivery is harmless;
 * a poison record ends up on booking-service's dead-letter topic.
 */
@EmbeddedKafka(partitions = 1, topics = {"skybook.payment.events", "skybook.payment.events.booking-service.DLT",
        OutboxBookingEventPublisher.TOPIC})
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=true")
class SagaOverKafkaTest extends BookingTestSupport {

    record PaymentCompleted(String paymentId, String bookingReference, String purpose, BigDecimal amount,
                            String currency) {
    }

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private EventCodec codec;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private EmbeddedKafkaBroker broker;

    private String createBooking() {
        InventoryStubs.stubHold(INVENTORY, 21L, START.plus(Duration.ofDays(10)), START.plus(Duration.ofMinutes(15)),
                List.of("12A", "12B"), "110.00");
        InventoryStubs.stubSagaPartners(INVENTORY, "pay_kafka_1", List.of("1761111111111", "1762222222222"));
        return given().header("Authorization", bearer("cust-kafka", Roles.CUSTOMER))
                .header("Idempotency-Key", "kafka-saga-0001")
                .contentType(ContentType.JSON).body("""
                        {"flightId":21,"contactEmail":"ada@example.test","passengers":[
                          {"firstName":"Ada","lastName":"Lovelace","dateOfBirth":"1990-12-10","seatNumber":"12A"},
                          {"firstName":"Alan","lastName":"Turing","dateOfBirth":"1992-06-23","seatNumber":"12B"}]}""")
                .when().post("/api/v1/bookings")
                .then().statusCode(201)
                .extract().path("bookingReference");
    }

    private String status(String reference) {
        return given().header("Authorization", bearer("cust-kafka", Roles.CUSTOMER))
                .when().get("/api/v1/bookings/{ref}", reference)
                .then().statusCode(200).extract().path("status");
    }

    @Test
    void paymentCompletedDrivesTheBookingToConfirmed() throws Exception {
        String reference = createBooking();
        assertThat(status(reference)).isEqualTo("PENDING_PAYMENT");

        String event = codec.encode(EventEnvelope.builder("PaymentCompleted",
                        new PaymentCompleted("pay_kafka_1", reference, "BOOKING", new BigDecimal("220.00"), "USD"))
                .producer("payment-service").aggregate("Booking", reference).correlationId("corr-kafka-1")
                .build(Clock.systemUTC()));
        kafka.send("skybook.payment.events", reference, event).get();

        await().atMost(Duration.ofSeconds(30)).until(() -> "CONFIRMED".equals(status(reference)));
        given().header("Authorization", bearer("cust-kafka", Roles.CUSTOMER))
                .when().get("/api/v1/bookings/{ref}", reference)
                .then().body("passengers.ticketNumber",
                        org.hamcrest.Matchers.contains("1761111111111", "1762222222222"));

        // Redelivery (at-least-once) changes nothing and does not call the GDS again.
        kafka.send("skybook.payment.events", reference, event).get();
        Thread.sleep(2_000);
        assertThat(status(reference)).isEqualTo("CONFIRMED");
        INVENTORY.verify(1, postRequestedFor(urlPathEqualTo("/gds/v1/tickets")));

        // The booking's own events leave through the outbox, in order, keyed by reference.
        relay.relayPending();
        List<String> types = new ArrayList<>();
        try (Consumer<String, String> consumer = consumer("verify-booking-events", OutboxBookingEventPublisher.TOPIC)) {
            for (ConsumerRecord<String, String> record : KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10))) {
                if (reference.equals(record.key())) {
                    types.add(codec.eventType(record.value()));
                }
            }
        }
        assertThat(types).containsExactly("BookingCreated", "SeatsHeld", "TicketIssued");
    }

    @Test
    void poisonRecordsGoToTheDeadLetterTopic() throws Exception {
        kafka.send("skybook.payment.events", "bad", "{definitely not an event").get();

        try (Consumer<String, String> consumer = consumer("verify-dlt",
                "skybook.payment.events.booking-service.DLT")) {
            ConsumerRecord<String, String> dead = KafkaTestUtils.getSingleRecord(consumer,
                    "skybook.payment.events.booking-service.DLT", Duration.ofSeconds(30));
            assertThat(dead.value()).isEqualTo("{definitely not an event");
        }
    }

    private Consumer<String, String> consumer(String group, String topic) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, group, false);
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, topic);
        return consumer;
    }
}
