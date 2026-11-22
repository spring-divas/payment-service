package org.spring.divas.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.spring.divas.payment.feature.payment.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentMapper paymentMapper;

    @InjectMocks
    private PaymentServiceImpl paymentService;

    private Payment payment;
    private PaymentResponseDto responseDto;
    private PaymentRequestDto requestDto;

    @BeforeEach
    void setUp() {
        requestDto = new PaymentRequestDto(100L);
        payment = Payment.builder()
                .id(1L)
                .orderId(100L)
                .status(PaymentStatus.PENDING)
                .idempotencyKey("test-key-1")
                .createdAt(LocalDateTime.now())
                .build();
        responseDto = new PaymentResponseDto(1L, 100L, PaymentStatus.PENDING, payment.getCreatedAt());
    }

    @Test
    void create_shouldCreateNewPaymentWhenNotExists() {
        when(paymentRepository.findByIdempotencyKey("test-key-1")).thenReturn(Optional.empty());
        when(paymentMapper.toEntity(requestDto)).thenReturn(payment);
        when(paymentRepository.save(payment)).thenReturn(payment);
        when(paymentMapper.toResponse(payment)).thenReturn(responseDto);

        PaymentResponseDto result = paymentService.create(requestDto, "test-key-1");

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.orderId()).isEqualTo(100L);
        assertThat(result.status()).isEqualTo(PaymentStatus.PENDING);
        verify(paymentRepository).save(payment);
    }

    @Test
    void create_shouldReturnExistingPaymentForSameIdempotencyKey() {
        when(paymentRepository.findByIdempotencyKey("test-key-1")).thenReturn(Optional.of(payment));
        when(paymentMapper.toResponse(payment)).thenReturn(responseDto);

        PaymentResponseDto result = paymentService.create(requestDto, "test-key-1");

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void getAll_shouldReturnListOfPayments() {
        when(paymentRepository.findAll()).thenReturn(List.of(payment));
        when(paymentMapper.toResponse(payment)).thenReturn(responseDto);

        List<PaymentResponseDto> result = paymentService.getAll();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().orderId()).isEqualTo(100L);
    }

    @Test
    void getById_shouldReturnPaymentWhenFound() {
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(paymentMapper.toResponse(payment)).thenReturn(responseDto);

        PaymentResponseDto result = paymentService.getById(1L);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
    }

    @Test
    void getById_shouldThrowWhenNotFound() {
        when(paymentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getById(99L))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void delete_shouldDeleteWhenFound() {
        when(paymentRepository.existsById(1L)).thenReturn(true);

        paymentService.delete(1L);

        verify(paymentRepository).deleteById(1L);
    }

    @Test
    void delete_shouldThrowWhenNotFound() {
        when(paymentRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> paymentService.delete(99L))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void update_shouldUpdateWhenFound() {
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(payment)).thenReturn(payment);
        when(paymentMapper.toResponse(payment)).thenReturn(responseDto);

        PaymentResponseDto result = paymentService.update(1L, requestDto);

        assertThat(result).isNotNull();
        verify(paymentRepository).save(payment);
    }

    @Test
    void update_shouldThrowWhenNotFound() {
        when(paymentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.update(99L, requestDto))
                .isInstanceOf(PaymentNotFoundException.class);
    }
}
