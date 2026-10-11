package escuelaing.edu.co.truckdar.load_allocation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckdar.load_allocation.dto.event.EventEnvelope;
import escuelaing.edu.co.truckdar.load_allocation.dto.event.FreightLoadEventPayload;
import escuelaing.edu.co.truckdar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckdar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckdar.load_allocation.model.OutboxEvent;
import escuelaing.edu.co.truckdar.load_allocation.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxEventService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void enqueueLoadEvent(FreightLoad load, String eventType) {
        FreightLoadEventPayload payload = FreightLoadEventPayload.builder()
                .loadId(load.getId())
                .generatorCompanyId(load.getGeneratorCompanyId())
                .cargoDescription(load.getCargoDescription())
                .weightTons(load.getWeightTons())
                .requiredTruckType(load.getRequiredTruckType().name())
                .originCity(load.getOriginCity())
                .destinationCity(load.getDestinationCity())
                .offeredBudget(load.getOfferedBudget())
                .loadStatus(load.getStatus().name())
                .assignmentStatus(load.getAssignmentStatus())
                .assignedDriverId(load.getAssignedDriverId())
                .assignedDriverName(load.getAssignedDriverName())
                .assignedVehiclePlate(load.getAssignedVehiclePlate())
                .assignmentAttempts(load.getAssignmentAttempts())
                .excludedDriverIds(load.getDiscardedDriverIds() == null
                        ? List.of() : List.copyOf(load.getDiscardedDriverIds()))
                .build();

        EventEnvelope<FreightLoadEventPayload> envelope = EventEnvelope.<FreightLoadEventPayload>builder()
                .eventId(UUID.randomUUID())
                .eventType(eventType)
                .occurredAt(Instant.now())
                .correlationId(UUID.randomUUID())
                .version(1)
                .producer("load-allocation")
                .payload(payload)
                .build();

        try {
            repository.save(OutboxEvent.builder()
                    .id(envelope.getEventId())
                    .aggregateType("FreightLoad")
                    .aggregateId(load.getId().toString())
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(envelope))
                    .occurredAt(envelope.getOccurredAt())
                    .createdAt(Instant.now())
                    .attempts(0)
                    .build());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("No se pudo serializar el evento para Outbox", exception);
        }
    }
}
