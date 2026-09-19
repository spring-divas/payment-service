package org.spring.divas.payment.feature.payment;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;

    @Override
    public PaymentResponseDto create(PaymentRequestDto dto) {
        Payment payment = paymentMapper.toEntity(dto);
        Payment saved = paymentRepository.save(payment);
        return paymentMapper.toResponse(saved);
    }

    @Override
    public List<PaymentResponseDto> getAll() {
        return paymentRepository.findAll()
                .stream()
                .map(paymentMapper::toResponse)
                .toList();
    }

    @Override
    public PaymentResponseDto getById(Long id) {

        Payment found = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));

        return paymentMapper.toResponse(found);
    }

    @Override
    public void delete(Long id) {

        if (!paymentRepository.existsById(id)) {
            throw new PaymentNotFoundException(id);
        }

        paymentRepository.deleteById(id);
    }

    @Override
    public PaymentResponseDto update(Long id, PaymentRequestDto dto) {

        Payment found = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));

        found.setOrderId(dto.getOrderId());
        Payment saved = paymentRepository.save(found);
        return paymentMapper.toResponse(saved);
    }
}