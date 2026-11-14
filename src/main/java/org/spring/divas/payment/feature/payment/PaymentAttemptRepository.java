package org.spring.divas.payment.feature.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {
    List<PaymentAttempt> findByPaymentIdOrderByAttemptNumberAsc(Long paymentId);
    List<PaymentAttempt> findByOrderIdOrderByAttemptNumberAsc(Long orderId);
}
