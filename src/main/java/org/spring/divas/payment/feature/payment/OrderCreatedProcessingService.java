package org.spring.divas.payment.feature.payment;

import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderCreatedProcessingService {

    private final PaymentRepository paymentRepository;
    private final ProcessedEventRepository processedEventRepository;

    @Observed(name = "payment.order-created.processing", contextualName = "process-order-created")
    @Transactional
    public void process(OrderCreatedEvent event) {
        if (event.eventId() == null || event.orderId() == null) {
            throw new IllegalArgumentException("OrderCreatedEvent must contain eventId and orderId");
        }

        if (processedEventRepository.existsByEventId(event.eventId())) {
            log.info("Ignoring duplicate event eventId={}, orderId={}", event.eventId(), event.orderId());
            return;
        }

        String idempotencyKey = "order-created-" + event.eventId();

        Payment payment = Payment.builder()
                .orderId(event.orderId())
                .status(PaymentStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .build();
        paymentRepository.save(payment);

        ProcessedEvent processedEvent = ProcessedEvent.builder()
                .eventId(event.eventId())
                .eventType("OrderCreated")
                .build();
        processedEventRepository.save(processedEvent);

        log.info("Created pending payment for orderId={}, eventId={}", event.orderId(), event.eventId());
    }
}