package escuelaing.edu.co.truckcar.load_allocation.sync;

import escuelaing.edu.co.truckcar.load_allocation.exception.AssignmentAccessDeniedException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ingesta por lotes de los eventos de viaje registrados sin conexión por la PWA del conductor. */
@RestController
@RequestMapping("/api/v1/loads/sync")
@RequiredArgsConstructor
public class SyncController {

    private final LoadSyncService syncService;

    /** El conductor es siempre el del JWT (X-Auth-User del gateway): un lote no puede actuar por otro. */
    @PostMapping("/batch")
    public ResponseEntity<SyncBatchResponse> ingest(
            @RequestHeader(value = "X-Auth-User", required = false) String authenticatedUserId,
            @Valid @RequestBody SyncBatchRequest request) {
        if (authenticatedUserId == null || authenticatedUserId.isBlank()) {
            throw new AssignmentAccessDeniedException("Se requiere un usuario autenticado para sincronizar eventos", true);
        }
        return ResponseEntity.ok(syncService.process(authenticatedUserId, request));
    }
}
