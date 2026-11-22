package org.spring.divas.payment.feature.payment;

import jakarta.validation.constraints.NotNull;

public record PaymentRequestDto(
        @NotNull Long orderId
) {}
