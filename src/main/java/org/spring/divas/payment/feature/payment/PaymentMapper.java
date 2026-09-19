package org.spring.divas.payment.feature.payment;

import org.springframework.stereotype.Component;

@Component
public class PaymentMapper {

    public PaymentResponseDto toResponse(Payment payment) {
        if (payment == null) {
            return null;
        }

        return new PaymentResponseDto(
                payment.getId(),
                payment.getOrderId(),
                payment.getStatus(),
                payment.getCreatedAt()
        );
    }

    public Payment toEntity(PaymentRequestDto request) {
        if (request == null) {
            return null;
        }

        return Payment.builder()
                .orderId(request.getOrderId())
                .status(PaymentStatus.PENDING)
                .build();
    }
}