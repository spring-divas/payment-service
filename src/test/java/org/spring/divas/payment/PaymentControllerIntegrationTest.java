package org.spring.divas.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spring.divas.payment.feature.payment.Payment;
import org.spring.divas.payment.feature.payment.PaymentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentRepository paymentRepository;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
    }

    @Test
    void shouldCreatePayment() throws Exception {
        mockMvc.perform(
                        post("/api/payment")
                                .contextPath("/api")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", "create-test-1")
                                .content("""
                                        {
                                          "orderId": 1
                                        }
                                        """)
                )
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.orderId").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void shouldReturnSamePaymentForSameIdempotencyKey() throws Exception {
        String idempotencyKey = "same-key-1";

        String firstResponse = mockMvc.perform(
                        post("/api/payment")
                                .contextPath("/api")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", idempotencyKey)
                                .content("""
                                        {
                                          "orderId": 1
                                        }
                                        """)
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String secondResponse = mockMvc.perform(
                        post("/api/payment")
                                .contextPath("/api")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", idempotencyKey)
                                .content("""
                                        {
                                          "orderId": 1
                                        }
                                        """)
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertEquals(
                firstResponse,
                secondResponse
        );

        org.junit.jupiter.api.Assertions.assertEquals(
                1,
                paymentRepository.count()
        );
    }

    @Test
    void shouldReturnProblemDetailWhenIdempotencyKeyIsMissing() throws Exception {
        mockMvc.perform(
                        post("/api/payment")
                                .contextPath("/api")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "orderId": 1
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Missing Request Header"));
    }

    @Test
    void shouldReturnProblemDetailWhenIdempotencyKeyIsBlank() throws Exception {
        mockMvc.perform(
                        post("/api/payment")
                                .contextPath("/api")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", "")
                                .content("""
                                        {
                                          "orderId": 1
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid Idempotency-Key"));
    }

    @Test
    void shouldReturnProblemDetailWhenPaymentDoesNotExist() throws Exception {
        mockMvc.perform(
                        get("/api/payment/999999")
                                .contextPath("/api")
                )
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Payment Not Found"));
    }

    @Test
    void shouldReturnProblemDetailWhenValidationFails() throws Exception {
        mockMvc.perform(
                        post("/api/payment")
                                .contextPath("/api")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", "validation-test")
                                .content("""
                                        {
                                          "orderId": null
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    @Test
    void shouldReturnCorrelationId() throws Exception {
        String correlationId = "payment-test-correlation-id";

        mockMvc.perform(
                        get("/api/payment/999999")
                                .contextPath("/api")
                                .header("X-Correlation-Id", correlationId)
                )
                .andExpect(status().isNotFound())
                .andExpect(header().string(
                        "X-Correlation-Id",
                        correlationId
                ));
    }
}
