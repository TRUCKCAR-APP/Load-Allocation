package escuelaing.edu.co.truckcar.load_allocation.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.DeliveryConfirmationRequest;
import escuelaing.edu.co.truckcar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckcar.load_allocation.exception.FreightLoadNotFoundException;
import escuelaing.edu.co.truckcar.load_allocation.exception.InvalidLoadOperationException;
import escuelaing.edu.co.truckcar.load_allocation.exception.ProofOfDeliveryEvidenceException;
import escuelaing.edu.co.truckcar.load_allocation.service.IFreightLoadService;
import escuelaing.edu.co.truckcar.load_allocation.service.ProofOfDeliveryService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Aplica en orden (occurredAt, seq) los eventos de viaje que el conductor registró sin señal: trip.started y
 * trip.delivered. Cada evento se registra y aplica en una sola transacción, así un reenvío no lo duplica y una falla a
 * mitad del lote no deja eventos aplicados sin registrar (ni al revés).
 */
@Slf4j
@Service
public class LoadSyncService {

    public static final String TRIP_STARTED = "trip.started";
    public static final String TRIP_DELIVERED = "trip.delivered";

    private static final Comparator<SyncBatchRequest.Event> CHRONOLOGICAL = Comparator
            .comparing(SyncBatchRequest.Event::getOccurredAt)
            .thenComparing(event -> event.getSeq() == null ? 0L : event.getSeq());

    private final IngestionLedger ledger;
    private final IFreightLoadService loadService;
    private final ProofOfDeliveryService proofOfDeliveryService;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final TransactionTemplate transaction;
    private final TransactionTemplate separateTransaction;

    public LoadSyncService(IngestionLedger ledger, IFreightLoadService loadService,
                           ProofOfDeliveryService proofOfDeliveryService, ObjectMapper objectMapper,
                           Validator validator, PlatformTransactionManager transactionManager) {
        this.ledger = ledger;
        this.loadService = loadService;
        this.proofOfDeliveryService = proofOfDeliveryService;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.transaction = new TransactionTemplate(transactionManager);
        this.separateTransaction = new TransactionTemplate(transactionManager);
        this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Un error de infraestructura (base de datos, almacenamiento) se propaga: el lote responde 5xx, la PWA lo
     * reintenta y los eventos ya aplicados vuelven como duplicados.
     */
    public SyncBatchResponse process(String driverId, SyncBatchRequest request) {
        SyncBatchResponse response = new SyncBatchResponse();
        request.getEvents().stream().sorted(CHRONOLOGICAL).forEach(event -> {
            try {
                Boolean fresh = transaction.execute(status -> {
                    if (!ledger.register(driverId, event, IngestionLedger.APPLIED, null)) {
                        return false;
                    }
                    apply(driverId, event);
                    return true;
                });
                (Boolean.TRUE.equals(fresh) ? response.getAccepted() : response.getDuplicates()).add(event.getClientEventId());
            } catch (InvalidLoadOperationException | AssignmentAccessDeniedException | FreightLoadNotFoundException
                     | ProofOfDeliveryEvidenceException | IllegalArgumentException rejection) {
                // La transacción del evento se revirtió; el rechazo queda registrado aparte para responder igual
                // si el lote se reenvía.
                Boolean recorded = separateTransaction.execute(status ->
                        ledger.register(driverId, event, IngestionLedger.REJECTED, rejection.getMessage()));
                if (Boolean.TRUE.equals(recorded)) {
                    response.getRejected().add(new SyncBatchResponse.Rejected(event.getClientEventId(),
                            rejection.getMessage()));
                } else {
                    response.getDuplicates().add(event.getClientEventId());
                }
                log.info("Evento offline {} ({}) del conductor {} rechazado: {}", event.getClientEventId(),
                        event.getKind(), driverId, rejection.getMessage());
            }
        });
        log.info("Lote offline del conductor {}: {} aplicados, {} duplicados, {} rechazados", driverId,
                response.getAccepted().size(), response.getDuplicates().size(), response.getRejected().size());
        return response;
    }

    private void apply(String driverId, SyncBatchRequest.Event event) {
        Long loadId = loadId(event.getPayload());
        LocalDateTime occurredAt = LocalDateTime.ofInstant(event.getOccurredAt(), ZoneId.systemDefault());
        switch (event.getKind()) {
            case TRIP_STARTED -> loadService.startTrip(loadId, driverId, occurredAt);
            case TRIP_DELIVERED -> {
                DeliveryConfirmationRequest pod = deliveryOf(event.getPayload());
                proofOfDeliveryService.verifyEvidence(loadId, pod);
                loadService.deliverLoad(loadId, driverId, pod, occurredAt);
            }
            default -> throw new IllegalArgumentException("Tipo de evento no admitido por Load-Allocation: "
                    + event.getKind());
        }
    }

    private static Long loadId(JsonNode payload) {
        JsonNode loadId = payload.path("loadId");
        if (!loadId.canConvertToLong()) {
            throw new IllegalArgumentException("El evento debe indicar loadId");
        }
        return loadId.asLong();
    }

    private DeliveryConfirmationRequest deliveryOf(JsonNode payload) {
        DeliveryConfirmationRequest pod;
        // loadId identifica la carga del evento; el resto del payload es la prueba de entrega (deserialización
        // estricta: un campo desconocido es un error del cliente).
        if (!payload.isObject()) {
            throw new IllegalArgumentException("La prueba de entrega debe ser un objeto JSON");
        }
        com.fasterxml.jackson.databind.node.ObjectNode fields = ((com.fasterxml.jackson.databind.node.ObjectNode) payload).deepCopy();
        fields.remove("loadId");
        try {
            pod = objectMapper.treeToValue(fields, DeliveryConfirmationRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Prueba de entrega mal formada: " + e.getOriginalMessage());
        }
        Set<ConstraintViolation<DeliveryConfirmationRequest>> violations = validator.validate(pod);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream().map(ConstraintViolation::getMessage)
                    .sorted().collect(Collectors.joining("; ")));
        }
        return pod;
    }
}
