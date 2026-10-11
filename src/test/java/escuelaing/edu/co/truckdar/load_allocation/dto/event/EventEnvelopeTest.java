package escuelaing.edu.co.truckdar.load_allocation.dto.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventEnvelopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void serializesTheVersionedEnvelopeAndNestedPayload() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        EventEnvelope<FreightLoadEventPayload> envelope = EventEnvelope.<FreightLoadEventPayload>builder()
                .eventId(eventId)
                .eventType("load.published")
                .occurredAt(Instant.parse("2026-10-10T23:00:00Z"))
                .correlationId(correlationId)
                .version(1)
                .producer("load-allocation")
                .payload(FreightLoadEventPayload.builder()
                        .loadId(42L)
                        .weightTons(30.0)
                        .requiredTruckType("TRACTOMULA_3S3")
                        .offeredBudget(new BigDecimal("1500000.00"))
                        .loadStatus("AVAILABLE")
                        .build())
                .build();

        String json = objectMapper.writeValueAsString(envelope);

        assertThat(json).contains("\"eventId\":\"" + eventId + "\"");
        assertThat(json).contains("\"eventType\":\"load.published\"");
        assertThat(json).contains("\"occurredAt\":\"2026-10-10T23:00:00Z\"");
        assertThat(json).contains("\"correlationId\":\"" + correlationId + "\"");
        assertThat(json).contains("\"version\":1");
        assertThat(json).contains("\"producer\":\"load-allocation\"");
        assertThat(json).contains("\"payload\"");
        assertThat(json).contains("\"loadId\":42");
        assertThat(json).doesNotContain("\"timestamp\"");
    }
}
