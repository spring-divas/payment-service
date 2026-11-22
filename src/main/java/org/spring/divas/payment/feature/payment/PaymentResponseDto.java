package org.spring.divas.payment.feature.payment;

import java.time.LocalDateTime;

public record PaymentResponseDto(
        Long id,
        Long orderId,
        PaymentStatus status,
        LocalDateTime createdAt
) {}
