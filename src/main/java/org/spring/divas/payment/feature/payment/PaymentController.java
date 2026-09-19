package org.spring.divas.payment.feature.payment;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@AllArgsConstructor
@RequestMapping("/payment")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponseDto create(@Valid @RequestBody PaymentRequestDto dto) {
        return paymentService.create(dto);
    }

    @GetMapping
    public List<PaymentResponseDto> getAll() {
        return paymentService.getAll();
    }

    @GetMapping("/{id}")
    public PaymentResponseDto getById(@PathVariable Long id) {
        return paymentService.getById(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        paymentService.delete(id);
    }

    @PutMapping("/{id}")
    public PaymentResponseDto update(
            @PathVariable Long id,
            @Valid @RequestBody PaymentRequestDto dto) {
        return paymentService.update(id, dto);
    }
}