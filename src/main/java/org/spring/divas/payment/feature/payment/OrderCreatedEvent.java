package org.spring.divas.payment.feature.payment;

import java.time.Instant;
import java.util.UUID;

public record OrderCreatedEvent(
        UUID eventId,
        Long orderId,
        Instant occurredAt
) {
}