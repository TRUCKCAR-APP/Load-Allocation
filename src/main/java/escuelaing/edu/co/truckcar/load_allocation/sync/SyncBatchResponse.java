package escuelaing.edu.co.truckcar.load_allocation.sync;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Resultado por evento. La PWA borra de su cola los aceptados, los duplicados (ya procesados en un envío anterior)
 * y los rechazados (no válidos: reintentarlos no cambia el resultado); el resto se reintenta.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncBatchResponse {

    @Builder.Default
    private List<UUID> accepted = new ArrayList<>();
    @Builder.Default
    private List<UUID> duplicates = new ArrayList<>();
    @Builder.Default
    private List<Rejected> rejected = new ArrayList<>();

    public record Rejected(UUID clientEventId, String reason) {
    }
}
