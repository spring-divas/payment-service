package org.spring.divas.payment.feature.payment.grpc;

import com.google.protobuf.Timestamp;
import org.spring.divas.payment.v1.Money;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class PaymentProtoMapper {

    private PaymentProtoMapper() {
    }

    public static Money toMoney(BigDecimal amount, String currency) {
        if (amount == null) {
            return Money.newBuilder()
                    .setCurrencyCode(currency != null ? currency : "USD")
                    .build();
        }
        long units = amount.longValue();
        int nanos = amount.remainder(BigDecimal.ONE).movePointRight(9).intValue();
        return Money.newBuilder()
                .setCurrencyCode(currency != null ? currency : "USD")
                .setUnits(units)
                .setNanos(nanos)
                .build();
    }

    public static BigDecimal toBigDecimal(Money money) {
        if (money == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(money.getUnits())
                .add(BigDecimal.valueOf(money.getNanos(), 9));
    }

    public static Timestamp toTimestamp(LocalDateTime ldt) {
        if (ldt == null) {
            ldt = LocalDateTime.now(ZoneId.of("Europe/Kyiv"));
        }
        long seconds = ldt.toEpochSecond(ZoneOffset.UTC);
        int nanos = ldt.getNano();
        return Timestamp.newBuilder()
                .setSeconds(seconds)
                .setNanos(nanos)
                .build();
    }

    public static org.spring.divas.payment.v1.PaymentStatus toProtoStatus(
            org.spring.divas.payment.feature.payment.PaymentStatus status) {
        if (status == null) {
            return org.spring.divas.payment.v1.PaymentStatus.PAYMENT_STATUS_UNSPECIFIED;
        }
        return switch (status) {
            case PENDING -> org.spring.divas.payment.v1.PaymentStatus.PAYMENT_STATUS_PENDING;
            case PAID -> org.spring.divas.payment.v1.PaymentStatus.PAYMENT_STATUS_PAID;
            case FAILED -> org.spring.divas.payment.v1.PaymentStatus.PAYMENT_STATUS_FAILED;
            case REFUNDED -> org.spring.divas.payment.v1.PaymentStatus.PAYMENT_STATUS_UNSPECIFIED;
            case CANCELLED -> org.spring.divas.payment.v1.PaymentStatus.PAYMENT_STATUS_CANCELLED;
        };
    }

    public static org.spring.divas.payment.feature.payment.PaymentStatus toDomainStatus(
            org.spring.divas.payment.v1.PaymentStatus status) {
        if (status == null) {
            return org.spring.divas.payment.feature.payment.PaymentStatus.PENDING;
        }
        return switch (status) {
            case PAYMENT_STATUS_PAID ->
                    org.spring.divas.payment.feature.payment.PaymentStatus.PAID;
            case PAYMENT_STATUS_FAILED ->
                    org.spring.divas.payment.feature.payment.PaymentStatus.FAILED;
            case PAYMENT_STATUS_CANCELLED ->
                    org.spring.divas.payment.feature.payment.PaymentStatus.CANCELLED;
            case PAYMENT_STATUS_PENDING, PAYMENT_STATUS_UNSPECIFIED, UNRECOGNIZED ->
                    org.spring.divas.payment.feature.payment.PaymentStatus.PENDING;
        };
    }
}
