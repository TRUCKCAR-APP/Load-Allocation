package escuelaing.edu.co.truckcar.load_allocation.sync;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lote de eventos que la PWA registró sin conexión (cola durable en IndexedDB). Mismo contrato en fleets-drivers,
 * Load-Allocation y Community-Alerts: cada servicio recibe en su /sync/batch los tipos de evento que le pertenecen.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncBatchRequest {

    @NotEmpty(message = "El lote no puede estar vacío")
    @Size(max = 200, message = "Un lote tiene como máximo 200 eventos")
    private List<@Valid Event> events;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Event {
        /** UUID v4 generado en el dispositivo: clave de idempotencia junto con el conductor. */
        @NotNull(message = "clientEventId es obligatorio")
        private UUID clientEventId;

        @NotBlank(message = "kind es obligatorio")
        @Size(max = 40)
        private String kind;

        /** Hora del dispositivo en que ocurrió el evento (no la de la sincronización). */
        @NotNull(message = "occurredAt es obligatorio")
        private Instant occurredAt;

        /** Secuencia creciente por viaje en el dispositivo: desempata eventos con la misma hora. */
        @PositiveOrZero
        private Long seq;

        @NotNull(message = "payload es obligatorio")
        private JsonNode payload;
    }
}
