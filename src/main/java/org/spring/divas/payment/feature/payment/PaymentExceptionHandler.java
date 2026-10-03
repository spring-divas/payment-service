package org.spring.divas.payment.feature.payment;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.net.URI;

@RestControllerAdvice
public class PaymentExceptionHandler {

    @ExceptionHandler(PaymentNotFoundException.class)
    public ProblemDetail handlePaymentNotFound(PaymentNotFoundException exception) {

        return createProblemDetail(
                HttpStatus.NOT_FOUND,
                "Payment Not Found",
                exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException exception) {

        return createProblemDetail(
                HttpStatus.BAD_REQUEST,
                "Validation Error",
                "Request validation failed"
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException exception) {

        return createProblemDetail(
                HttpStatus.BAD_REQUEST,
                "Validation Error",
                exception.getMessage()
        );
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception exception) {

        return createProblemDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "An unexpected error occurred"
        );
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ProblemDetail handleInvalidIdempotencyKey(InvalidIdempotencyKeyException ex) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage()
        );
        problem.setTitle("Invalid Idempotency-Key");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    private ProblemDetail createProblemDetail(HttpStatus status, String title, String detail) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("timestamp", Instant.now());

        return problem;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingRequestHeader(MissingRequestHeaderException ex,
                                                                    HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Missing Request Header");
        problem.setDetail("Required request header '" + ex.getHeaderName() + "' is missing.");
        problem.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(problem);
    }
}