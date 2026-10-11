package org.spring.divas.payment;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spring.divas.payment.feature.payment.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {"order-created", "order-created-dlt"}
)
class OrderCreatedConsumerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    private EmbeddedKafkaBroker embeddedKafka;

    @MockitoSpyBean
    private OrderCreatedProcessingService processingService;

    @BeforeEach
    void cleanDatabase() {
        processedEventRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void duplicateEventCreatesOnlyOnePayment() throws Exception {
        UUID eventId = UUID.randomUUID();
        OrderCreatedEvent event = new OrderCreatedEvent(eventId, 123L, Instant.now());

        String payload = objectMapper.writeValueAsString(event);
        kafkaTemplate.send(new ProducerRecord<>("order-created", "123", payload)).get();
        kafkaTemplate.send(new ProducerRecord<>("order-created", "123", payload)).get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertThat(paymentRepository.findAll())
                            .hasSize(1);

                    assertThat(processedEventRepository
                            .existsByEventId(eventId))
                            .isTrue();
                });

        assertThat(paymentRepository.findAll().getFirst().getOrderId())
                .isEqualTo(123L);

        assertThat(paymentRepository.findAll().getFirst().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void permanentlyFailingEventIsSentToDlt() throws Exception {
        OrderCreatedEvent invalidEvent = new OrderCreatedEvent(UUID.randomUUID(), null, Instant.now());

        String payload = objectMapper.writeValueAsString(invalidEvent);
        Map<String, Object> consumerProperties = KafkaTestUtils.consumerProps(
                embeddedKafka,
                "dlt-test-" + UUID.randomUUID(),
                false
        );

        consumerProperties.put(
                org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        consumerProperties.put(
                org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        consumerProperties.put(
                org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest");

        try (Consumer<String, String> dltConsumer = new KafkaConsumer<>(consumerProperties)) {
            embeddedKafka.consumeFromAnEmbeddedTopic(
                    dltConsumer,
                    "order-created-dlt"
            );

            kafkaTemplate.send(new ProducerRecord<>("order-created", "invalid", payload)).get();

            ConsumerRecord<String, String> dltRecord = null;
            long deadline = System.currentTimeMillis() + 20000;

            while (System.currentTimeMillis() < deadline && dltRecord == null) {
                var records = dltConsumer.poll(Duration.ofMillis(500));

                if (!records.isEmpty()) {
                    dltRecord = records.iterator().next();
                }
            }

            assertThat(dltRecord).isNotNull();
            assertThat(dltRecord.value()).contains("\"orderId\":null");
        }
    }


    @Test
    void temporaryFailureIsRetriedAndEventuallyProcessed() throws Exception {
        UUID eventId = UUID.randomUUID();
        OrderCreatedEvent event = new OrderCreatedEvent(eventId, 456L, Instant.now());
        String payload = objectMapper.writeValueAsString(event);

        AtomicInteger attempts = new AtomicInteger();

        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException("Simulated temporary failure");
            }

            return invocation.callRealMethod();
        }).when(processingService).process(any(OrderCreatedEvent.class));

        Map<String, Object> consumerProperties = KafkaTestUtils.consumerProps(
                embeddedKafka,
                "retry-dlt-test-" + UUID.randomUUID(),
                false
        );
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (Consumer<String, String> dltConsumer = new KafkaConsumer<>(consumerProperties)) {
            embeddedKafka.consumeFromAnEmbeddedTopic(dltConsumer, "order-created-dlt");
            kafkaTemplate.send(new ProducerRecord<>("order-created", "456", payload)).get();

            Awaitility.await()
                    .atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> {
                        assertThat(attempts.get()).isEqualTo(2);
                        assertThat(paymentRepository.findAll()).hasSize(1);
                        assertThat(
                                processedEventRepository.existsByEventId(eventId)
                        ).isTrue();
                    });

            Payment payment = paymentRepository.findAll().getFirst();

            assertThat(payment.getOrderId()).isEqualTo(456L);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);

            boolean eventFoundInDlt = false;
            long deadline = System.currentTimeMillis() + 2000;

            while (System.currentTimeMillis() < deadline) {
                var records = dltConsumer.poll(Duration.ofMillis(200));

                for (var kafkaRecord : records) {
                    if (kafkaRecord.value() != null
                            && kafkaRecord.value().contains(eventId.toString())) {
                        eventFoundInDlt = true;
                        break;
                    }
                }

                if (eventFoundInDlt) {
                    break;
                }
            }

            assertThat(eventFoundInDlt).isFalse();
        }
    }


}