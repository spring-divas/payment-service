package org.spring.divas.payment.feature.payment;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class PaymentRequestDto {

    @NotNull
    private Long orderId;
}