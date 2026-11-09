package com.skybook.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import com.skybook.common.event.EventEnvelope;
import com.skybook.common.test.MutableClock;

import tools.jackson.databind.json.JsonMapper;

/**
 * Chaos test: the Kafka broker freezes (container paused) while the business keeps writing events.
 *
 * <p>Expected behaviour of the transactional outbox (ADR-0013): the business transactions still commit,
 * the relay records the failure and stops at the first unsent event (preserving order), and once the
 * broker is back every event is delivered, in order, without loss. Delivery is at-least-once, so the
 * assertions are on distinct event ids; consumers de-duplicate with {@link ProcessedEventStore}.
 */
@Testcontainers(disabledWithoutDocker = true)
class OutboxRelayChaosIT {

    private static final String TOPIC = "chaos.events";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2");

    record Numbered(int sequence) {
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-12-01T08:00:00Z"));
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private OutboxWriter outbox;
    private OutboxRelay relay;
    private DefaultKafkaProducerFactory<String, String> producers;
    private final List<String> appendedIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:chaos-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/messaging-tables.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            if (!admin.listTopics().names().get().contains(TOPIC)) {
                admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
            }
        }
        // Short client timeouts so the outage is detected in seconds rather than the 2-minute defaults.
        producers = new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1_500,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3_000));
        EventCodec codec = new EventCodec(JsonMapper.builder().build());
        outbox = new OutboxWriter(jdbc, codec, clock);
        relay = new OutboxRelay(jdbc, new KafkaTemplate<>(producers), tx,
                new MessagingProperties(50, "FOR UPDATE", Duration.ofSeconds(4), null, 0, null), clock);
    }

    @AfterEach
    void tearDown() {
        unpause(); // never leave the shared broker frozen for the next test
        producers.destroy();
    }

    @Test
    void brokerOutageLosesNothingAndKeepsOrder() {
        appendEvents(0, 5);
        assertThat(relay.relayBatch()).isEqualTo(5);

        pause();
        appendEvents(5, 10); // the business keeps committing while Kafka is down

        assertThat(relay.relayBatch()).as("nothing can be published during the outage").isZero();
        assertThat(unpublished()).isEqualTo(5);
        Map<String, Object> firstStuck = jdbc.queryForMap(
                "select attempts, last_error from outbox_event where published_at is null order by created_at limit 1");
        assertThat(((Number) firstStuck.get("attempts")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(firstStuck.get("last_error")).isNotNull();

        unpause();
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            relay.relayPending();
            assertThat(unpublished()).isZero();
        });

        List<String> delivered = consumeDistinctEventIds(10);
        assertThat(delivered).containsExactlyElementsOf(appendedIds);
    }

    private void appendEvents(int fromInclusive, int toExclusive) {
        for (int i = fromInclusive; i < toExclusive; i++) {
            int sequence = i;
            clock.advance(Duration.ofMillis(1)); // distinct created_at: the relay orders by it
            EventEnvelope<Numbered> event = EventEnvelope.builder("Numbered", new Numbered(sequence))
                    .producer("chaos-test").aggregate("Flight", "SB101").correlationId("chaos-" + sequence)
                    .build(clock);
            tx.executeWithoutResult(status -> outbox.append(TOPIC, event));
            appendedIds.add(event.eventId().toString());
        }
    }

    private int unpublished() {
        Integer count = jdbc.queryForObject("select count(*) from outbox_event where published_at is null",
                Integer.class);
        return count == null ? 0 : count;
    }

    /** Event ids in first-delivery order, ignoring at-least-once duplicates. */
    private static List<String> consumeDistinctEventIds(int expected) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "chaos-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Set<String> ids = new LinkedHashSet<>();
        JsonMapper json = JsonMapper.builder().build();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (ids.size() < expected && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    ids.add(json.readTree(record.value()).get("eventId").asString());
                }
            }
        }
        return List.copyOf(ids);
    }

    private static void pause() {
        DockerClientFactory.instance().client().pauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    private static void unpause() {
        var docker = DockerClientFactory.instance().client();
        boolean paused = Boolean.TRUE.equals(docker.inspectContainerCmd(KAFKA.getContainerId()).exec()
                .getState().getPaused());
        if (paused) {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }
    }
}
