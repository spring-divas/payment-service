package org.spring.divas.payment.feature.payment;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PaymentMapper {

    PaymentResponseDto toResponse(Payment payment);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", constant = "PENDING")
    @Mapping(target = "createdAt", ignore = true)
    Payment toEntity(PaymentRequestDto request);
}