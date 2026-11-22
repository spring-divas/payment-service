package org.spring.divas.payment;

import de.codecentric.spring.boot.chaos.monkey.configuration.AssaultException;
import de.codecentric.spring.boot.chaos.monkey.configuration.AssaultProperties;
import de.codecentric.spring.boot.chaos.monkey.configuration.ChaosMonkeySettings;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spring.divas.payment.feature.payment.OrderCreatedEvent;
import org.spring.divas.payment.feature.payment.Payment;
import org.spring.divas.payment.feature.payment.PaymentRepository;
import org.spring.divas.payment.feature.payment.ProcessedEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "chaos.monkey.watcher.service=false",
        "chaos.monkey.watcher.rest-controller=false",
        "chaos.monkey.watcher.beans=orderCreatedProcessingService",
        "spring.grpc.server.port=0"
})
@ActiveProfiles("chaos-monkey")
@EmbeddedKafka(
        partitions = 1,
        topics = {"order-created", "order-created-retry-1000", "order-created-dlt"}
)
class OrderCreatedChaosTest {

    private static final String TOPIC = "order-created";

    private static final String FIRST_RETRY_TOPIC = "order-created-retry-1000";

    private static final String DLT = "order-created-dlt";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    private EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    private ChaosMonkeySettings chaosMonkey;

    @BeforeEach
    void setUp() {
        processedEventRepository.deleteAll();
        paymentRepository.deleteAll();

        AssaultProperties assaults = chaosMonkey.getAssaultProperties();
        assaults.setLevel(1);
        assaults.setDeterministic(true);
        assaults.setLatencyActive(false);
        assaults.setExceptionsActive(true);
        assaults.setException(new AssaultException());

        listeners.getListenerContainers().stream()
                .filter(container -> List.of(container.getContainerProperties().getTopics())
                        .contains(TOPIC))
                .forEach(container -> ContainerTestUtils.waitForAssignment(container, 1));
    }

    @AfterEach
    void stopChaos() {
        chaosMonkey.getChaosMonkeyProperties().setEnabled(false);
    }

    @Test
    void temporaryOutageDelaysPaymentButDoesNotLoseIt() throws Exception {
        UUID eventId = UUID.randomUUID();
        chaosMonkey.getChaosMonkeyProperties().setEnabled(true);

        try (Consumer<String, String> retryConsumer = consumer(FIRST_RETRY_TOPIC)) {
            send(new OrderCreatedEvent(eventId, 501L, Instant.now()));

            awaitRecord(retryConsumer, eventId.toString());
        }
        assertThat(paymentRepository.count()).isZero();

        chaosMonkey.getChaosMonkeyProperties().setEnabled(false);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(paymentRepository.findAll())
                    .singleElement()
                    .extracting(Payment::getOrderId)
                    .isEqualTo(501L);
            assertThat(processedEventRepository.existsByEventId(eventId)).isTrue();
        });
    }

    @Test
    void permanentOutageSendsEventToDltWithoutCreatingPayment() throws Exception {
        UUID eventId = UUID.randomUUID();
        chaosMonkey.getChaosMonkeyProperties().setEnabled(true);

        try (Consumer<String, String> dltConsumer = consumer(DLT)) {
            send(new OrderCreatedEvent(eventId, 502L, Instant.now()));

            ConsumerRecord<String, String> dltRecord = awaitRecord(dltConsumer, eventId.toString());

            assertThat(dltRecord.value()).contains("\"orderId\":502");
        }
        assertThat(paymentRepository.count()).isZero();
        assertThat(processedEventRepository.existsByEventId(eventId)).isFalse();
    }

    private void send(OrderCreatedEvent event) throws Exception {
        String payload = objectMapper.writeValueAsString(event);
        kafkaTemplate.send(new ProducerRecord<>(TOPIC, event.orderId().toString(), payload)).get();
    }

    private Consumer<String, String> consumer(String topic) {
        Map<String, Object> properties = KafkaTestUtils.consumerProps(
                embeddedKafka, "chaos-" + UUID.randomUUID(), false);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        Consumer<String, String> consumer = new KafkaConsumer<>(properties);
        embeddedKafka.consumeFromAnEmbeddedTopic(consumer, topic);
        return consumer;
    }

    private ConsumerRecord<String, String> awaitRecord(
            Consumer<String, String> consumer, String marker) {
        return await().atMost(Duration.ofSeconds(20))
                .until(() -> StreamSupport.stream(
                                consumer.poll(Duration.ofMillis(200)).spliterator(), false)
                        .filter(consumerRecord -> consumerRecord.value() != null
                                && consumerRecord.value().contains(marker))
                        .findFirst(), Optional::isPresent)
                .orElseThrow();
    }
}
