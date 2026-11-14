package org.spring.divas.payment.feature.payment.grpc;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.spring.divas.payment.feature.payment.Payment;
import org.spring.divas.payment.feature.payment.PaymentAttempt;
import org.spring.divas.payment.feature.payment.PaymentAttemptRepository;
import org.spring.divas.payment.feature.payment.PaymentRepository;
import org.spring.divas.payment.feature.payment.PaymentStatus;
import org.spring.divas.payment.v1.OrderCancellation;
import org.spring.divas.payment.v1.OrderPaymentInfo;
import org.spring.divas.payment.v1.PaymentConfirmation;
import org.spring.divas.payment.v1.PaymentServiceGrpc;
import org.spring.divas.payment.v1.PaymentStreamRequest;
import org.spring.divas.payment.v1.PaymentStreamResponse;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;


@Slf4j
@GrpcService
@RequiredArgsConstructor
public class PaymentGrpcService extends PaymentServiceGrpc.PaymentServiceImplBase {

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final TransactionTemplate transactionTemplate;

    // Counter for simulating transient failures (UNAVAILABLE) during retry testing
    private final AtomicInteger transientFailuresRemaining = new AtomicInteger(0);

    public void setTransientFailuresCount(int count) {
        this.transientFailuresRemaining.set(count);
    }

    /**
     * Bidirectional streaming: Order <--> Payment (Real-time payment).
     * 1. Order sends order information.
     * 2. Payment creates transaction.
     * 3. Payment sends PENDING.
     * 4. Order acknowledges receipt.
     * 5. Payment sends PAID or FAILED.
     * 6. Order updates order status.
     * 7. Order sends cancellation status if order is CANCELLED.
     */
    @Override
    public StreamObserver<PaymentStreamRequest> processPaymentStream(
            StreamObserver<PaymentStreamResponse> responseObserver) {

        log.info("[gRPC Bidirectional] New payment stream opened");

        return new StreamObserver<>() {
            private Long currentOrderId;
            private Long currentPaymentId;
            private boolean simulateFailure;
            private int attemptCount = 0;

            @Override
            public void onNext(PaymentStreamRequest request) {
                if (Context.current().isCancelled()) {
                    log.warn("[gRPC] Client cancelled call or deadline exceeded");
                    return;
                }

                switch (request.getRequestPayloadCase()) {
                    case ORDER_INFO -> handleOrderInfo(request.getOrderInfo());
                    case CONFIRMATION -> handleConfirmation(request.getConfirmation());
                    case CANCELLATION -> handleCancellation(request.getCancellation());
                    case REQUESTPAYLOAD_NOT_SET ->
                            log.warn("[gRPC] Received empty payload");
                    default ->
                            log.warn("[gRPC] Unknown message type");
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("[gRPC] Client stream error: {}", t.getMessage());
            }

            @Override
            public void onCompleted() {
                log.info("[gRPC Bidirectional] Client completed stream.");
                responseObserver.onCompleted();
            }

            private void handleOrderInfo(OrderPaymentInfo info) {
                this.currentOrderId = info.getOrderId();
                this.simulateFailure = info.getSimulateFailure();

                log.info("[gRPC Step 1] Received order: orderId={}, amount={} {}",
                        currentOrderId,
                        PaymentProtoMapper.toBigDecimal(info.getAmount()),
                        info.getAmount().getCurrencyCode());

                // Simulate transient unavailability (UNAVAILABLE) for retry testing
                if (info.getSimulateUnavailable()
                        || transientFailuresRemaining.getAndDecrement() > 0) {
                    log.warn("[gRPC Server] Simulating UNAVAILABLE status for retry testing");
                    responseObserver.onError(Status.UNAVAILABLE
                            .withDescription("Payment service temporarily unavailable")
                            .asRuntimeException());
                    return;
                }

                // Step 2: Payment creates transaction and first payment attempt
                transactionTemplate.executeWithoutResult(status -> {
                    BigDecimal totalAmount = PaymentProtoMapper.toBigDecimal(info.getAmount());

                    Payment payment = paymentRepository.findByOrderId(currentOrderId)
                            .orElseGet(() -> Payment.builder()
                                    .orderId(currentOrderId)
                                    .amount(totalAmount)
                                    .status(PaymentStatus.PENDING)
                                    .idempotencyKey(info.getIdempotencyKey().isBlank()
                                            ? "order-" + currentOrderId
                                            : info.getIdempotencyKey())
                                    .build());

                    payment.setStatus(PaymentStatus.PENDING);
                    payment = paymentRepository.save(payment);
                    currentPaymentId = payment.getId();

                    List<PaymentAttempt> existing = paymentAttemptRepository
                            .findByPaymentIdOrderByAttemptNumberAsc(payment.getId());
                    attemptCount = existing.size() + 1;

                    PaymentAttempt attempt = PaymentAttempt.builder()
                            .payment(payment)
                            .orderId(currentOrderId)
                            .attemptNumber(attemptCount)
                            .status(PaymentStatus.PENDING)
                            .createdAt(LocalDateTime.now())
                            .build();

                    paymentAttemptRepository.save(attempt);
                    log.info("[gRPC Step 2] Created payment id={} and attempt #{}",
                            payment.getId(), attemptCount);
                });

                // Step 3: Payment sends PENDING
                PaymentStreamResponse pendingResponse = PaymentStreamResponse.newBuilder()
                        .setPaymentId(currentPaymentId)
                        .setOrderId(currentOrderId)
                        .setStatus(org.spring.divas.payment.v1.PaymentStatus.PAYMENT_STATUS_PENDING)
                        .setMessage("Payment created in PENDING status, awaiting confirmation.")
                        .setTimestamp(PaymentProtoMapper.toTimestamp(LocalDateTime.now()))
                        .setAttemptNumber(attemptCount)
                        .setRequiresConfirmation(true)
                        .build();

                log.info("[gRPC Step 3] Sent PENDING for orderId={}", currentOrderId);
                sendResponse(pendingResponse);
            }

            private void handleConfirmation(PaymentConfirmation confirmation) {
                log.info("[gRPC Step 4] Received confirmation from Order: confirmed={}",
                        confirmation.getConfirmed());

                if (!confirmation.getConfirmed()) {
                    log.warn("[gRPC Step 4] Received negative confirmation");
                    return;
                }

                // Step 5: Payment processes operation and sends PAID or FAILED
                org.spring.divas.payment.v1.PaymentStatus finalStatus;
                String failureReason = null;
                String responseMessage;

                if (simulateFailure) {
                    finalStatus = org.spring.divas.payment.v1.PaymentStatus.PAYMENT_STATUS_FAILED;
                    failureReason = "Payment declined: Insufficient funds or issuer declined";
                    responseMessage = "Payment declined by acquirer";
                } else {
                    finalStatus = org.spring.divas.payment.v1.PaymentStatus.PAYMENT_STATUS_PAID;
                    responseMessage = "Payment successfully confirmed by banking system";
                }

                String finalReason = failureReason;
                transactionTemplate.executeWithoutResult(status -> {
                    Payment payment = paymentRepository.findById(currentPaymentId).orElse(null);
                    if (payment != null) {
                        PaymentStatus domainStatus = (finalStatus
                                == org.spring.divas.payment.v1.PaymentStatus.PAYMENT_STATUS_PAID)
                                ? PaymentStatus.PAID : PaymentStatus.FAILED;

                        payment.setStatus(domainStatus);
                        paymentRepository.save(payment);

                        PaymentAttempt attempt = PaymentAttempt.builder()
                                .payment(payment)
                                .orderId(currentOrderId)
                                .attemptNumber(attemptCount)
                                .status(domainStatus)
                                .failureReason(finalReason)
                                .createdAt(LocalDateTime.now())
                                .build();

                        paymentAttemptRepository.save(attempt);
                        log.info("[gRPC Step 5] Payment paymentId={} status={}",
                                currentPaymentId, domainStatus);
                    }
                });

                PaymentStreamResponse.Builder responseBuilder = PaymentStreamResponse.newBuilder()
                        .setPaymentId(currentPaymentId)
                        .setOrderId(currentOrderId)
                        .setStatus(finalStatus)
                        .setMessage(responseMessage)
                        .setTimestamp(PaymentProtoMapper.toTimestamp(LocalDateTime.now()))
                        .setAttemptNumber(attemptCount)
                        .setRequiresConfirmation(false);

                if (failureReason != null) {
                    responseBuilder.setFailureReason(failureReason);
                }

                PaymentStreamResponse finalResponse = responseBuilder.build();
                log.info("[gRPC Step 5] Sent result {} for orderId={}",
                        finalStatus, currentOrderId);
                sendResponse(finalResponse);
            }

            private void handleCancellation(OrderCancellation cancellation) {
                log.info("[gRPC Step 7] Received cancellation from Order: orderId={}, reason='{}'",
                        cancellation.getOrderId(), cancellation.getReason());

                transactionTemplate.executeWithoutResult(status -> {
                    Payment payment = paymentRepository.findByOrderId(cancellation.getOrderId())
                            .orElse(null);

                    if (payment != null) {
                        payment.setStatus(PaymentStatus.CANCELLED);
                        paymentRepository.save(payment);

                        List<PaymentAttempt> existing = paymentAttemptRepository
                                .findByPaymentIdOrderByAttemptNumberAsc(payment.getId());
                        int nextAttempt = existing.size() + 1;

                        PaymentAttempt cancelAttempt = PaymentAttempt.builder()
                                .payment(payment)
                                .orderId(cancellation.getOrderId())
                                .attemptNumber(nextAttempt)
                                .status(PaymentStatus.CANCELLED)
                                .failureReason("Order cancelled: " + cancellation.getReason())
                                .createdAt(LocalDateTime.now())
                                .build();

                        paymentAttemptRepository.save(cancelAttempt);
                        currentPaymentId = payment.getId();
                        attemptCount = nextAttempt;
                        log.info("[gRPC Step 7] Payment paymentId={} cancelled", payment.getId());
                    }
                });

                Long resolvedPaymentId = currentPaymentId != null
                        ? currentPaymentId : cancellation.getPaymentId();

                PaymentStreamResponse cancelResponse = PaymentStreamResponse.newBuilder()
                        .setPaymentId(resolvedPaymentId)
                        .setOrderId(cancellation.getOrderId())
                        .setStatus(org.spring.divas.payment.v1.PaymentStatus
                                .PAYMENT_STATUS_CANCELLED)
                        .setMessage("Payment cancelled as requested by Order.")
                        .setTimestamp(PaymentProtoMapper.toTimestamp(LocalDateTime.now()))
                        .setAttemptNumber(attemptCount)
                        .build();

                sendResponse(cancelResponse);
            }

            private void sendResponse(PaymentStreamResponse response) {
                synchronized (responseObserver) {
                    responseObserver.onNext(response);
                }
            }
        };
    }
}
