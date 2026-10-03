package org.spring.divas.payment.feature.payment;


public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key must not be empty");
    }
}