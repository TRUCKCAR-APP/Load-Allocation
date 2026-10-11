package escuelaing.edu.co.truckdar.load_allocation.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned envelope shared by TruckDar domain events.
 *
 * @param <T> event-specific payload
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventEnvelope<T> {

    private UUID eventId;
    private String eventType;
    private Instant occurredAt;
    private UUID correlationId;
    private int version;
    private String producer;
    private T payload;
}
