package org.spring.divas.payment;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.template.observation-enabled=true"
})
@EmbeddedKafka(
        partitions = 1,
        topics = "trace-context-test"
)
@DirtiesContext
@Import(KafkaTracePropagationTest.TraceTestConfiguration.class)
class KafkaTracePropagationTest {

    private static final String TRACE_ID =
            "1234567890abcdef1234567890abcdef";

    private static final String PARENT_SPAN_ID =
            "1234567890abcdef";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private TraceCaptureListener traceCaptureListener;

    @Autowired
    private Tracer tracer;
    @Test
    void shouldContinueTraceFromKafkaHeaders() throws Exception {
        Span parentSpan = tracer.nextSpan()
                .name("test-parent")
                .start();

        String expectedTraceId = parentSpan.context().traceId();

        try (Tracer.SpanInScope ignored = tracer.withSpan(parentSpan)) {
            kafkaTemplate.send(
                    "trace-context-test",
                    "test-key",
                    "test-payload"
            ).get(10, TimeUnit.SECONDS);
        } finally {
            parentSpan.end();
        }

        assertThat(traceCaptureListener.awaitTrace()).isTrue();

        assertThat(traceCaptureListener.getTraceId())
                .isEqualTo(expectedTraceId);
    }

    @TestConfiguration
    static class TraceTestConfiguration {

        @Bean
        TraceCaptureListener traceCaptureListener(Tracer tracer) {
            return new TraceCaptureListener(tracer);
        }
    }

    static class TraceCaptureListener {

        private final Tracer tracer;
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicReference<String> traceId =
                new AtomicReference<>();

        TraceCaptureListener(Tracer tracer) {
            this.tracer = tracer;
        }

        @KafkaListener(
                topics = "trace-context-test",
                groupId = "trace-propagation-test"
        )
        public void receive(String payload) {
            Span currentSpan = tracer.currentSpan();

            if (currentSpan != null) {
                traceId.set(currentSpan.context().traceId());
            }

            latch.countDown();
        }

        boolean awaitTrace() throws InterruptedException {
            return latch.await(10, TimeUnit.SECONDS);
        }

        String getTraceId() {
            return traceId.get();
        }
    }
}