package org.spring.divas.payment.feature.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCreatedConsumer {

    private final OrderCreatedProcessingService processingService;
    private final ObjectMapper objectMapper;

    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(delay = 1000, multiplier = 2.0),
            dltTopicSuffix = "-dlt"
    )
    @KafkaListener(
            topics = "order-created",
            groupId = "payment-service"
    )
    public void consume(String payload) throws Exception {
        OrderCreatedEvent event = objectMapper.readValue(payload, OrderCreatedEvent.class);
        log.info("Received OrderCreatedEvent: eventId={}, orderId={}", event.eventId(), event.orderId());
        processingService.process(event);
    }

    @DltHandler
    public void handleDlt(String payload) {
        log.error("OrderCreatedEvent moved to DLT: {}", payload);
    }
}