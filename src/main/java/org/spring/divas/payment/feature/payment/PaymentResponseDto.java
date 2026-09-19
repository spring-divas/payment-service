package org.spring.divas.payment.feature.payment;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class PaymentResponseDto {

    private Long id;

    private Long orderId;

    private PaymentStatus status;

    private LocalDateTime createdAt;
}