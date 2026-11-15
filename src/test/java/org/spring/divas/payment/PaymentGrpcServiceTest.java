package org.spring.divas.payment;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spring.divas.payment.feature.payment.Payment;
import org.spring.divas.payment.feature.payment.PaymentAttempt;
import org.spring.divas.payment.feature.payment.PaymentAttemptRepository;
import org.spring.divas.payment.feature.payment.PaymentRepository;
import org.spring.divas.payment.feature.payment.PaymentStatus;
import org.spring.divas.payment.feature.payment.grpc.PaymentGrpcService;
import org.spring.divas.payment.feature.payment.grpc.PaymentProtoMapper;
import org.spring.divas.payment.v1.OrderCancellation;
import org.spring.divas.payment.v1.OrderPaymentInfo;
import org.spring.divas.payment.v1.OrderPaymentItem;
import org.spring.divas.payment.v1.PaymentConfirmation;
import org.spring.divas.payment.v1.PaymentServiceGrpc;
import org.spring.divas.payment.v1.PaymentStreamRequest;
import org.spring.divas.payment.v1.PaymentStreamResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureTestGrpcTransport
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created-dlt"})
@ImportGrpcClients(types = {
        PaymentServiceGrpc.PaymentServiceBlockingStub.class,
        PaymentServiceGrpc.PaymentServiceStub.class
})
class PaymentGrpcServiceTest {

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @Autowired
    private PaymentServiceGrpc.PaymentServiceStub asyncStub;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private PaymentGrpcService paymentGrpcService;

    @BeforeEach
    void setUp() {
        paymentAttemptRepository.deleteAll();
        paymentRepository.deleteAll();
        paymentGrpcService.setTransientFailuresCount(0);
    }

    @Test
    @DisplayName("Bidirectional stream: full order-to-payment lifecycle (steps 1-7)")
    void testBidirectionalPaymentFlowSuccess() throws Exception {
        Long orderId = 101L;
        CountDownLatch pendingLatch = new CountDownLatch(1);
        CountDownLatch paidLatch = new CountDownLatch(1);
        CountDownLatch cancelLatch = new CountDownLatch(1);

        List<PaymentStreamResponse> responses = new ArrayList<>();
        AtomicReference<StreamObserver<PaymentStreamRequest>> requestObserverRef =
                new AtomicReference<>();

        StreamObserver<PaymentStreamRequest> requestObserver = asyncStub.processPaymentStream(
                new StreamObserver<>() {
                    @Override
                    public void onNext(PaymentStreamResponse response) {
                        responses.add(response);
                        if (response.getStatus() == org.spring.divas.payment.v1.PaymentStatus
                                .PAYMENT_STATUS_PENDING) {
                            pendingLatch.countDown();
                            // Step 4: Order acknowledges receipt of PENDING
                            requestObserverRef.get().onNext(PaymentStreamRequest.newBuilder()
                                    .setConfirmation(PaymentConfirmation.newBuilder()
                                            .setPaymentId(response.getPaymentId())
                                            .setOrderId(orderId)
                                            .setConfirmed(true)
                                            .build())
                                    .build());

                        } else if (response.getStatus() == org.spring.divas.payment.v1.PaymentStatus
                                .PAYMENT_STATUS_PAID) {
                            paidLatch.countDown();

                            // Step 7: Order sends status update CANCELLED
                            requestObserverRef.get().onNext(PaymentStreamRequest.newBuilder()
                                    .setCancellation(OrderCancellation.newBuilder()
                                            .setOrderId(orderId)
                                            .setPaymentId(response.getPaymentId())
                                            .setReason("Customer changed mind")
                                            .build())
                                    .build());

                        } else if (response.getStatus() == org.spring.divas.payment.v1.PaymentStatus
                                .PAYMENT_STATUS_CANCELLED) {
                            cancelLatch.countDown();
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                }
        );
        requestObserverRef.set(requestObserver);

        // Step 1: Order sends order information
        requestObserver.onNext(PaymentStreamRequest.newBuilder()
                .setOrderInfo(OrderPaymentInfo.newBuilder()
                        .setOrderId(orderId)
                        .setUserId(42L)
                        .setAmount(PaymentProtoMapper.toMoney(new BigDecimal("120.00"), "USD"))
                        .setCustomerEmail("client@divas.ua")
                        .setIdempotencyKey("order-101-test")
                        .addItems(OrderPaymentItem.newBuilder()
                                .setDishId(1L)
                                .setQuantity(2)
                                .setPrice(PaymentProtoMapper.toMoney(
                                        new BigDecimal("60.00"), "USD"))
                                .build())
                        .setSimulateFailure(false)
                        .build())
                .build());

        assertThat(pendingLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(paidLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(cancelLatch.await(5, TimeUnit.SECONDS)).isTrue();

        requestObserver.onCompleted();

        // Verify persistence in Payment and PaymentAttempt
        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);

        List<PaymentAttempt> attempts = paymentAttemptRepository
                .findByOrderIdOrderByAttemptNumberAsc(orderId);
        assertThat(attempts).hasSize(3); // 1: PENDING, 2: PAID, 3: CANCELLED
        assertThat(attempts.get(0).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(attempts.get(1).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(attempts.get(2).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    @DisplayName("Bidirectional stream: payment failure scenario (FAILED) with reason recording")
    void testBidirectionalPaymentFlowFailure() throws Exception {
        Long orderId = 102L;
        CountDownLatch failedLatch = new CountDownLatch(1);
        List<PaymentStreamResponse> responses = new ArrayList<>();
        AtomicReference<StreamObserver<PaymentStreamRequest>> requestObserverRef =
                new AtomicReference<>();

        StreamObserver<PaymentStreamRequest> requestObserver = asyncStub.processPaymentStream(
                new StreamObserver<>() {
                    @Override
                    public void onNext(PaymentStreamResponse response) {
                        responses.add(response);
                        if (response.getStatus() == org.spring.divas.payment.v1.PaymentStatus
                                .PAYMENT_STATUS_PENDING) {
                            // Step 4: acknowledge receipt immediately
                            requestObserverRef.get().onNext(PaymentStreamRequest.newBuilder()
                                    .setConfirmation(PaymentConfirmation.newBuilder()
                                            .setPaymentId(response.getPaymentId())
                                            .setOrderId(orderId)
                                            .setConfirmed(true)
                                            .build())
                                    .build());
                        } else if (response.getStatus() == org.spring.divas.payment.v1.PaymentStatus
                                .PAYMENT_STATUS_FAILED) {
                            failedLatch.countDown();
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                }
        );
        requestObserverRef.set(requestObserver);

        // Step 1: send order with simulate_failure = true
        requestObserver.onNext(PaymentStreamRequest.newBuilder()
                .setOrderInfo(OrderPaymentInfo.newBuilder()
                        .setOrderId(orderId)
                        .setUserId(55L)
                        .setAmount(PaymentProtoMapper.toMoney(new BigDecimal("999.99"), "USD"))
                        .setSimulateFailure(true)
                        .build())
                .build());

        assertThat(failedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        PaymentStreamResponse failedResponse = responses.stream()
                .filter(r -> r.getStatus() == org.spring.divas.payment.v1.PaymentStatus
                        .PAYMENT_STATUS_FAILED)
                .findFirst()
                .orElseThrow();

        assertThat(failedResponse.hasFailureReason()).isTrue();
        assertThat(failedResponse.getFailureReason()).contains("Insufficient funds");

        // Verify attempt saved with failure reason
        List<PaymentAttempt> attempts = paymentAttemptRepository
                .findByOrderIdOrderByAttemptNumberAsc(orderId);
        assertThat(attempts).isNotEmpty();
        PaymentAttempt lastAttempt = attempts.getLast();
        assertThat(lastAttempt.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(lastAttempt.getFailureReason()).isNotNull();

        requestObserver.onCompleted();
    }

    @Test
    @DisplayName("Bidirectional stream: server transient UNAVAILABLE handling")
    void testBidirectionalTransientUnavailable() throws Exception {
        Long orderId = 103L;
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        StreamObserver<PaymentStreamRequest> requestObserver = asyncStub.processPaymentStream(
                new StreamObserver<>() {
                    @Override
                    public void onNext(PaymentStreamResponse response) {
                    }

                    @Override
                    public void onError(Throwable t) {
                        errorRef.set(t);
                        errorLatch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                    }
                }
        );

        // Step 1: send order with simulate_unavailable = true
        requestObserver.onNext(PaymentStreamRequest.newBuilder()
                .setOrderInfo(OrderPaymentInfo.newBuilder()
                        .setOrderId(orderId)
                        .setAmount(PaymentProtoMapper.toMoney(new BigDecimal("50.00"), "USD"))
                        .setSimulateUnavailable(true)
                        .build())
                .build());

        assertThat(errorLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(errorRef.get()).isInstanceOf(StatusRuntimeException.class);
        StatusRuntimeException sre = (StatusRuntimeException) errorRef.get();
        assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }
}
