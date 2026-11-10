package escuelaing.edu.co.truckcar.load_allocation.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.DeliveryConfirmationRequest;
import escuelaing.edu.co.truckcar.load_allocation.exception.InvalidLoadOperationException;
import escuelaing.edu.co.truckcar.load_allocation.service.IFreightLoadService;
import escuelaing.edu.co.truckcar.load_allocation.service.ProofOfDeliveryService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoadSyncServiceTest {

    private static final String DRIVER = "driver-ana";
    private static final String SHA = "e".repeat(64);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private IngestionLedger ledger;
    private IFreightLoadService loads;
    private ProofOfDeliveryService proof;
    private LoadSyncService service;

    @BeforeEach
    void setUp() {
        ledger = mock(IngestionLedger.class);
        loads = mock(IFreightLoadService.class);
        proof = mock(ProofOfDeliveryService.class);
        service = new LoadSyncService(ledger, loads, proof, mapper,
                Validation.buildDefaultValidatorFactory().getValidator(), mock(PlatformTransactionManager.class));
    }

    private SyncBatchRequest.Event event(String kind, Instant at, long seq, ObjectNode payload) {
        return SyncBatchRequest.Event.builder().clientEventId(UUID.randomUUID()).kind(kind).occurredAt(at)
                .seq(seq).payload(payload).build();
    }

    private ObjectNode delivery() {
        ObjectNode payload = mapper.createObjectNode().put("loadId", 74).put("receiverName", "Bodega");
        payload.putObject("signature").put("objectKey", "pod/74/signature-" + SHA + ".png").put("sha256", SHA)
                .put("contentType", "image/png").put("sizeBytes", 4070);
        return payload;
    }

    @Test
    void appliesEventsInDeviceOrderWithTheirOfflineTimestamps() {
        when(ledger.register(anyString(), any(), anyString(), any())).thenReturn(true);
        Instant startedAt = Instant.parse("2026-10-31T14:00:00Z");
        Instant deliveredAt = Instant.parse("2026-10-31T16:30:00Z");
        SyncBatchRequest.Event delivered = event(LoadSyncService.TRIP_DELIVERED, deliveredAt, 2, delivery());
        SyncBatchRequest.Event started = event(LoadSyncService.TRIP_STARTED, startedAt, 1,
                mapper.createObjectNode().put("loadId", 74));

        SyncBatchResponse response = service.process(DRIVER, SyncBatchRequest.builder()
                .events(List.of(delivered, started)).build());

        assertThat(response.getAccepted()).containsExactly(started.getClientEventId(), delivered.getClientEventId());
        InOrder order = inOrder(loads);
        order.verify(loads).startTrip(74L, DRIVER, LocalDateTime.ofInstant(startedAt, ZoneId.systemDefault()));
        ArgumentCaptor<DeliveryConfirmationRequest> pod = ArgumentCaptor.forClass(DeliveryConfirmationRequest.class);
        order.verify(loads).deliverLoad(eq(74L), eq(DRIVER), pod.capture(),
                eq(LocalDateTime.ofInstant(deliveredAt, ZoneId.systemDefault())));
        // loadId del evento no forma parte de la prueba de entrega (deserialización estricta).
        assertThat(pod.getValue().getReceiverName()).isEqualTo("Bodega");
        assertThat(pod.getValue().getSignature().getSha256()).isEqualTo(SHA);
        verify(proof).verifyEvidence(eq(74L), eq(DRIVER), any());
    }

    @Test
    void alreadyIngestedEventIsReportedAsDuplicateWithoutReapplying() {
        when(ledger.register(anyString(), any(), anyString(), any())).thenReturn(false);
        SyncBatchRequest.Event started = event(LoadSyncService.TRIP_STARTED, Instant.now(), 1,
                mapper.createObjectNode().put("loadId", 74));

        SyncBatchResponse response = service.process(DRIVER, SyncBatchRequest.builder().events(List.of(started)).build());

        assertThat(response.getDuplicates()).containsExactly(started.getClientEventId());
        verify(loads, never()).startTrip(anyLong(), anyString(), any());
    }

    @Test
    void businessRejectionIsRecordedSoAResendGetsTheSameAnswer() {
        when(ledger.register(anyString(), any(), eq(IngestionLedger.APPLIED), any())).thenReturn(true);
        when(ledger.register(anyString(), any(), eq(IngestionLedger.REJECTED), anyString())).thenReturn(true);
        when(loads.startTrip(anyLong(), anyString(), any()))
                .thenThrow(new InvalidLoadOperationException("se esperaba status ASSIGNED"));
        SyncBatchRequest.Event started = event(LoadSyncService.TRIP_STARTED, Instant.now(), 1,
                mapper.createObjectNode().put("loadId", 74));

        SyncBatchResponse response = service.process(DRIVER, SyncBatchRequest.builder().events(List.of(started)).build());

        assertThat(response.getRejected()).singleElement().satisfies(r ->
                assertThat(r.reason()).contains("se esperaba status ASSIGNED"));
        verify(ledger).register(eq(DRIVER), eq(started), eq(IngestionLedger.REJECTED), anyString());
    }

    @Test
    void unknownKindAndMalformedDeliveryAreRejected() {
        when(ledger.register(anyString(), any(), anyString(), any())).thenReturn(true);
        ObjectNode bad = delivery();
        bad.put("signatureDataUrl", "data:image/png;base64,AAAA"); // formato antiguo: ya no se acepta

        SyncBatchResponse response = service.process(DRIVER, SyncBatchRequest.builder().events(List.of(
                event("trip.teleported", Instant.now(), 1, mapper.createObjectNode().put("loadId", 74)),
                event(LoadSyncService.TRIP_DELIVERED, Instant.now(), 2, bad))).build());

        assertThat(response.getRejected()).hasSize(2);
        verify(loads, never()).deliverLoad(anyLong(), anyString(), any(), any());
    }
}
