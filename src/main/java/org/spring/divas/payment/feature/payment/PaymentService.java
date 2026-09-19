package org.spring.divas.payment.feature.payment;

import java.util.List;

public interface PaymentService {

    PaymentResponseDto create(PaymentRequestDto dto);

    List<PaymentResponseDto> getAll();

    PaymentResponseDto getById(Long id);

    void delete(Long id);

    PaymentResponseDto update(Long id, PaymentRequestDto dto);
}