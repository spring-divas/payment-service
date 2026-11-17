package org.spring.divas.payment;

import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.V4Interaction;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.spring.divas.payment.feature.payment.OrderCreatedConsumer;
import org.spring.divas.payment.feature.payment.OrderCreatedProcessingService;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "order-service", providerType = ProviderType.ASYNCH)
class OrderCreatedPactTest {

    private static final String ISO_INSTANT_PATTERN =
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z";

    @Pact(consumer = "payment-service", provider = "order-service")
    public V4Pact createOrderPact(PactBuilder builder) {
        var content = new PactDslJsonBody()
                .uuid("eventId")
                .integerType("orderId", 123L)
                .stringMatcher("occurredAt", ISO_INSTANT_PATTERN, "2000-01-31T15:00:00Z");
        return builder.expectsToReceiveMessageInteraction(
                "an OrderCreated event",
                message -> message.withContents(
                        contents -> contents.withContent(content)
                )).toPact();
    }

    @Test
    @PactTestFor(pactMethod = "createOrderPact")
    void consumerProcessesContractMessage(List<V4Interaction.AsynchronousMessage> messages) throws Exception {
        var processingService = mock(OrderCreatedProcessingService.class);
        var consumer = new OrderCreatedConsumer(processingService, new ObjectMapper());
        consumer.consume(new String(messages.getFirst().contentsAsBytes(), StandardCharsets.UTF_8));
        verify(processingService).process(argThat(event ->
                event.eventId() != null && event.orderId() != null && event.occurredAt() != null
        ));
    }
}
