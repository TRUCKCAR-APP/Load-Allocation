package escuelaing.edu.co.truckcar.load_allocation.sync;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/**
 * Registro de eventos offline procesados (tabla ingested_events, clave (driver_id, client_event_id)). Insertar con
 * ON CONFLICT DO NOTHING es la deduplicación exacta: un reenvío del mismo evento no vuelve a aplicarse.
 */
@Component
@RequiredArgsConstructor
public class IngestionLedger {

    public static final String APPLIED = "APPLIED";
    public static final String REJECTED = "REJECTED";

    private final JdbcTemplate jdbc;

    /** true si el evento es nuevo (se registró ahora); false si ya estaba procesado. */
    public boolean register(String driverId, SyncBatchRequest.Event event, String status, String reason) {
        return jdbc.update("""
                INSERT INTO ingested_events (driver_id, client_event_id, kind, seq, occurred_at, status, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (driver_id, client_event_id) DO NOTHING
                """,
                driverId, event.getClientEventId(), event.getKind(), event.getSeq(),
                Timestamp.from(event.getOccurredAt()), status, truncate(reason)) == 1;
    }

    private static String truncate(String reason) {
        return reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500);
    }
}
