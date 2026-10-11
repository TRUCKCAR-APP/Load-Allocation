package escuelaing.edu.co.truckdar.load_allocation.service;

import escuelaing.edu.co.truckdar.load_allocation.dto.request.AssignDriverRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.request.CreateFreightLoadRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.response.FreightLoadResponse;
import escuelaing.edu.co.truckdar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckdar.load_allocation.exception.FreightLoadNotFoundException;
import escuelaing.edu.co.truckdar.load_allocation.exception.InvalidLoadOperationException;
import escuelaing.edu.co.truckdar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckdar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckdar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckdar.load_allocation.model.TruckTypeRequired;
import escuelaing.edu.co.truckdar.load_allocation.repository.FreightLoadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FreightLoadService implements IFreightLoadService {

    private static final Set<AssignmentStatus> REASSIGNABLE =
            EnumSet.of(AssignmentStatus.REJECTED, AssignmentStatus.TIMEOUT);

    private final FreightLoadRepository repository;
    private final OutboxEventService outboxEventService;

    @Value("${truckdar.assignment.max-attempts:5}")
    private int maxAssignmentAttempts = 5;

    @Override
    @Transactional
    public FreightLoadResponse publishLoad(CreateFreightLoadRequest request, UUID correlationId) {
        FreightLoad load = FreightLoad.builder()
                .correlationId(correlationId != null ? correlationId : UUID.randomUUID())
                .generatorCompanyId(request.getGeneratorCompanyId())
                .generatorCompanyName(request.getGeneratorCompanyName())
                .cargoDescription(request.getCargoDescription())
                .weightTons(request.getWeightTons())
                .volumeM3(request.getVolumeM3())
                .requiredTruckType(request.getRequiredTruckType())
                .originCity(request.getOriginCity())
                .originDepartment(request.getOriginDepartment())
                .originLatitude(request.getOriginLatitude())
                .originLongitude(request.getOriginLongitude())
                .destinationCity(request.getDestinationCity())
                .destinationDepartment(request.getDestinationDepartment())
                .destinationLatitude(request.getDestinationLatitude())
                .destinationLongitude(request.getDestinationLongitude())
                .offeredBudget(request.getOfferedBudget())
                .status(LoadStatus.AVAILABLE)
                .publishedAt(LocalDateTime.now())
                .build();

        FreightLoad saved = repository.save(load);
        outboxEventService.enqueueLoadEvent(saved, "load.published");
        log.info("Carga publicada #{} por {} ({} Tn)", saved.getId(), saved.getGeneratorCompanyName(), saved.getWeightTons());

        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public FreightLoadResponse assignDriver(Long loadId, AssignDriverRequest request) {
        FreightLoad load = repository.findById(loadId)
                .orElseThrow(() -> new FreightLoadNotFoundException("No se encontró la carga con ID: " + loadId));

        // Se asigna una carga nueva (sin propuesta) o se reabre una que se quedó sin conductores elegibles.
        boolean reopening = load.getAssignmentStatus() == AssignmentStatus.UNASSIGNED_NO_DRIVERS;
        if (load.getStatus() != LoadStatus.AVAILABLE || (load.getAssignmentStatus() != null && !reopening)) {
            throw new InvalidLoadOperationException("La carga #" + loadId + " no admite una nueva asignación (status="
                    + load.getStatus() + ", assignmentStatus=" + load.getAssignmentStatus() + ")");
        }
        if (load.getDiscardedDriverIds().contains(request.getDriverId())) {
            throw new InvalidLoadOperationException("El conductor " + request.getDriverId()
                    + " ya rechazó o dejó vencer la carga #" + loadId);
        }
        if (reopening) {
            // Nuevo ciclo de asignación: se reinicia el límite de intentos y se conservan los conductores descartados.
            load.setAssignmentAttempts(0);
            log.info("Carga #{} reabierta desde UNASSIGNED_NO_DRIVERS para el conductor {}", loadId, request.getDriverId());
        }

        FreightLoad updated = propose(load, request.getDriverId(), request.getDriverName(), request.getVehiclePlate());
        log.info("Propuesta de asignación creada para carga #{} y conductor {}", updated.getId(), request.getDriverName());

        return mapToResponse(updated);
    }

    /**
     * Aplica una nueva recomendación (agent.score.ready) sobre una carga REJECTED/TIMEOUT de forma idempotente.
     * Si se agotaron los intentos, la carga pasa a UNASSIGNED_NO_DRIVERS en lugar de proponerse otra vez.
     */
    @Override
    @Transactional
    public boolean proposeReassignment(Long loadId, String driverId, String driverName, String vehiclePlate) {
        FreightLoad load = repository.findById(loadId).orElse(null);
        if (load == null) {
            log.warn("Recomendación ignorada: la carga #{} no existe", loadId);
            return false;
        }
        if (!REASSIGNABLE.contains(load.getAssignmentStatus())) {
            log.info("Recomendación ignorada para carga #{} en estado {}", loadId, load.getAssignmentStatus());
            return false;
        }
        if (driverId == null || load.getDiscardedDriverIds().contains(driverId)) {
            log.info("Recomendación ignorada para carga #{}: el conductor {} ya fue descartado", loadId, driverId);
            return false;
        }
        if (attemptsOf(load) >= maxAssignmentAttempts) {
            markUnassigned(load, "se alcanzó el límite de " + maxAssignmentAttempts + " intentos");
            return false;
        }
        propose(load, driverId, driverName, vehiclePlate);
        log.info("Carga #{} reasignada al conductor {} (intento {}/{})",
                loadId, driverId, load.getAssignmentAttempts(), maxAssignmentAttempts);
        return true;
    }

    @Override
    @Transactional
    public boolean markNoDriversAvailable(Long loadId) {
        FreightLoad load = repository.findById(loadId).orElse(null);
        if (load == null || !REASSIGNABLE.contains(load.getAssignmentStatus())) {
            log.info("Aviso de falta de candidatos ignorado para carga #{}", loadId);
            return false;
        }
        markUnassigned(load, "el motor de asignación no encontró más candidatos");
        return true;
    }

    /** Vence la propuesta solo si sigue NOTIFIED desde antes del corte (evita expirar una propuesta recién renovada). */
    @Override
    @Transactional
    public boolean expireIfUnanswered(Long loadId, LocalDateTime notifiedBefore) {
        FreightLoad load = repository.findById(loadId).orElse(null);
        if (load == null
                || load.getAssignmentStatus() != AssignmentStatus.NOTIFIED
                || load.getAssignmentStatusChangedAt() == null
                || !load.getAssignmentStatusChangedAt().isBefore(notifiedBefore)) {
            return false;
        }
        discardCurrentDriver(load);
        load.setAssignmentStatus(AssignmentStatus.TIMEOUT);
        load.setAssignmentStatusChangedAt(LocalDateTime.now());
        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, "assignment.timeout");
        log.info("Propuesta de la carga #{} venció sin respuesta del conductor {}", loadId, load.getAssignedDriverId());
        return true;
    }

    private FreightLoad propose(FreightLoad load, String driverId, String driverName, String vehiclePlate) {
        load.setAssignedDriverId(driverId);
        load.setAssignedDriverName(driverName);
        load.setAssignedVehiclePlate(vehiclePlate);
        load.setAssignmentAttempts(attemptsOf(load) + 1);
        load.setAssignmentStatus(AssignmentStatus.PROPOSED);
        load.setAssignmentStatusChangedAt(LocalDateTime.now());
        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, "assignment.proposed");
        return updated;
    }

    private void markUnassigned(FreightLoad load, String reason) {
        load.setAssignmentStatus(AssignmentStatus.UNASSIGNED_NO_DRIVERS);
        load.setAssignmentStatusChangedAt(LocalDateTime.now());
        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, "assignment.unassigned");
        log.warn("Carga #{} queda sin conductor: {}", load.getId(), reason);
    }

    private void discardCurrentDriver(FreightLoad load) {
        if (load.getAssignedDriverId() != null) {
            load.getDiscardedDriverIds().add(load.getAssignedDriverId());
        }
    }

    private int attemptsOf(FreightLoad load) {
        return load.getAssignmentAttempts() == null ? 0 : load.getAssignmentAttempts();
    }

    @Override
    @Transactional
    public FreightLoadResponse notifyAssignment(Long loadId) {
        return transitionAssignment(loadId, AssignmentStatus.NOTIFIED, AssignmentStatus.PROPOSED,
                "assignment.notified");
    }

    /**
     * Aplica el evento assignment.notified de Notifications de forma idempotente:
     * ignora redeliveries (ya NOTIFIED), propuestas de otro conductor y estados que no admiten la transición.
     */
    @Override
    @Transactional
    public boolean applyAssignmentNotified(Long loadId, String notifiedDriverId) {
        FreightLoad load = repository.findById(loadId).orElse(null);
        if (load == null) {
            log.warn("assignment.notified ignorado: la carga #{} no existe", loadId);
            return false;
        }
        if (notifiedDriverId != null && !notifiedDriverId.equals(load.getAssignedDriverId())) {
            log.info("assignment.notified ignorado para carga #{}: notificó a {} pero la propuesta vigente es de {}",
                    loadId, notifiedDriverId, load.getAssignedDriverId());
            return false;
        }
        if (load.getAssignmentStatus() != AssignmentStatus.PROPOSED) {
            log.info("assignment.notified ignorado para carga #{} en estado {}", loadId, load.getAssignmentStatus());
            return false;
        }
        transitionAssignment(loadId, AssignmentStatus.NOTIFIED, AssignmentStatus.PROPOSED, "assignment.notified");
        return true;
    }

    @Override
    @Transactional
    public FreightLoadResponse acceptAssignment(Long loadId, String authenticatedUserId) {
        requireAssignedDriver(loadId, authenticatedUserId, "aceptar");
        FreightLoad load = transition(loadId, AssignmentStatus.ACCEPTED, AssignmentStatus.NOTIFIED);
        load.setStatus(LoadStatus.ASSIGNED);
        load.setAssignedAt(LocalDateTime.now());
        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, "assignment.accepted");
        return mapToResponse(updated);
    }

    @Override
    @Transactional
    public FreightLoadResponse rejectAssignment(Long loadId, String authenticatedUserId) {
        requireAssignedDriver(loadId, authenticatedUserId, "rechazar");
        FreightLoad load = transition(loadId, AssignmentStatus.REJECTED, AssignmentStatus.NOTIFIED);
        discardCurrentDriver(load);
        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, "assignment.rejected");
        return mapToResponse(updated);
    }

    /**
     * Solo el conductor propuesto puede aceptar o rechazar su carga. El api-gateway propaga el sujeto
     * del JWT en X-Auth-User (y descarta cualquier valor enviado por el cliente).
     */
    private void requireAssignedDriver(Long loadId, String authenticatedUserId, String action) {
        if (authenticatedUserId == null || authenticatedUserId.isBlank()) {
            throw new AssignmentAccessDeniedException("Se requiere un usuario autenticado para " + action
                    + " la carga #" + loadId, true);
        }
        FreightLoad load = repository.findById(loadId)
                .orElseThrow(() -> new FreightLoadNotFoundException("No se encontró la carga con ID: " + loadId));
        if (!authenticatedUserId.equals(load.getAssignedDriverId())) {
            throw new AssignmentAccessDeniedException("El usuario " + authenticatedUserId + " no es el conductor asignado a la carga #"
                    + loadId + "; no puede " + action + "la", false);
        }
    }

    @Override
    @Transactional
    public FreightLoadResponse timeoutAssignment(Long loadId) {
        FreightLoad load = repository.findById(loadId)
                .orElseThrow(() -> new FreightLoadNotFoundException("No se encontró la carga con ID: " + loadId));
        if (load.getAssignmentStatus() != AssignmentStatus.PROPOSED
                && load.getAssignmentStatus() != AssignmentStatus.NOTIFIED) {
            throw new InvalidLoadOperationException("La propuesta de la carga #" + loadId
                    + " no puede expirar desde el estado " + load.getAssignmentStatus());
        }
        discardCurrentDriver(load);
        load.setAssignmentStatus(AssignmentStatus.TIMEOUT);
        load.setAssignmentStatusChangedAt(LocalDateTime.now());
        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, "assignment.timeout");
        return mapToResponse(updated);
    }

    private FreightLoadResponse transitionAssignment(Long loadId, AssignmentStatus target,
                                                     AssignmentStatus expected, String eventType) {
        FreightLoad load = transition(loadId, target, expected);
        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, eventType);
        return mapToResponse(updated);
    }

    private FreightLoad transition(Long loadId, AssignmentStatus target, AssignmentStatus expected) {
        FreightLoad load = repository.findById(loadId)
                .orElseThrow(() -> new FreightLoadNotFoundException("No se encontró la carga con ID: " + loadId));
        if (load.getAssignmentStatus() != expected) {
            throw new InvalidLoadOperationException("Transición inválida para la carga #" + loadId
                    + ": se esperaba " + expected + " pero el estado actual es " + load.getAssignmentStatus());
        }
        load.setAssignmentStatus(target);
        load.setAssignmentStatusChangedAt(LocalDateTime.now());
        return load;
    }

    @Override
    @Transactional(readOnly = true)
    public FreightLoadResponse getLoadById(Long loadId) {
        return repository.findById(loadId)
                .map(this::mapToResponse)
                .orElseThrow(() -> new FreightLoadNotFoundException("No se encontró la carga con ID: " + loadId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FreightLoadResponse> getAvailableLoads(TruckTypeRequired truckType) {
        List<FreightLoad> results = (truckType != null)
                ? repository.findByStatusAndRequiredTruckType(LoadStatus.AVAILABLE, truckType)
                : repository.findByStatusOrderByPublishedAtDesc(LoadStatus.AVAILABLE);

        return results.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<FreightLoadResponse> getLoadsByCompany(String generatorCompanyId) {
        return repository.findByGeneratorCompanyIdOrderByPublishedAtDesc(generatorCompanyId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private FreightLoadResponse mapToResponse(FreightLoad entity) {
        return FreightLoadResponse.builder()
                .id(entity.getId())
                .generatorCompanyId(entity.getGeneratorCompanyId())
                .generatorCompanyName(entity.getGeneratorCompanyName())
                .cargoDescription(entity.getCargoDescription())
                .weightTons(entity.getWeightTons())
                .volumeM3(entity.getVolumeM3())
                .requiredTruckType(entity.getRequiredTruckType())
                .originCity(entity.getOriginCity())
                .originDepartment(entity.getOriginDepartment())
                .originLatitude(entity.getOriginLatitude())
                .originLongitude(entity.getOriginLongitude())
                .destinationCity(entity.getDestinationCity())
                .destinationDepartment(entity.getDestinationDepartment())
                .destinationLatitude(entity.getDestinationLatitude())
                .destinationLongitude(entity.getDestinationLongitude())
                .offeredBudget(entity.getOfferedBudget())
                .status(entity.getStatus())
                .assignmentStatus(entity.getAssignmentStatus())
                .assignedDriverId(entity.getAssignedDriverId())
                .assignedDriverName(entity.getAssignedDriverName())
                .assignedVehiclePlate(entity.getAssignedVehiclePlate())
                .publishedAt(entity.getPublishedAt())
                .assignedAt(entity.getAssignedAt())
                .assignmentStatusChangedAt(entity.getAssignmentStatusChangedAt())
                .correlationId(entity.getCorrelationId())
                .assignmentAttempts(entity.getAssignmentAttempts())
                .discardedDriverIds(entity.getDiscardedDriverIds() == null ? List.of() : List.copyOf(entity.getDiscardedDriverIds()))
                .build();
    }
}