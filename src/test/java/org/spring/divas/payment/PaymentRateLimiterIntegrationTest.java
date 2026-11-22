package org.spring.divas.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "resilience4j.ratelimiter.instances.paymentApi.limit-for-period=3",
        "resilience4j.ratelimiter.instances.paymentApi.limit-refresh-period=1m",
        "resilience4j.ratelimiter.instances.paymentApi.timeout-duration=0s"
})
@AutoConfigureMockMvc
class PaymentRateLimiterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRejectRequestsWithProblemDetailWhenRateLimitIsExceeded() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/payment")).andExpect(status().isOk());
        }

        mockMvc.perform(get("/payment"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too Many Requests"));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().is(not(HttpStatus.TOO_MANY_REQUESTS.value())));
    }
}
