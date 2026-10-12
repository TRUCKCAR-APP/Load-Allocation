package escuelaing.edu.co.truckdar.load_allocation.service;

import escuelaing.edu.co.truckdar.load_allocation.dto.request.AssignDriverRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.request.CancelLoadRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.request.CreateFreightLoadRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.request.DeliveryConfirmationRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.response.DriverCommitmentResponse;
import escuelaing.edu.co.truckdar.load_allocation.dto.response.FreightLoadResponse;
import escuelaing.edu.co.truckdar.load_allocation.dto.response.ProofOfDeliveryResponse;
import escuelaing.edu.co.truckdar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckdar.load_allocation.exception.FreightLoadNotFoundException;
import escuelaing.edu.co.truckdar.load_allocation.exception.InvalidLoadOperationException;
import escuelaing.edu.co.truckdar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckdar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckdar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckdar.load_allocation.model.ProofOfDeliveryMedia;
import escuelaing.edu.co.truckdar.load_allocation.model.TruckTypeRequired;
import escuelaing.edu.co.truckdar.load_allocation.repository.FreightLoadRepository;
import escuelaing.edu.co.truckdar.load_allocation.repository.ProofOfDeliveryMediaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
    /** Un conductor con una carga en estos status está ocupado: no puede aceptar ni recibir otra. */
    private static final Set<LoadStatus> DRIVER_COMMITTED = EnumSet.of(LoadStatus.ASSIGNED, LoadStatus.IN_TRANSIT);
    /** Propuesta vigente: el conductor debe responderla antes de recibir otra. */
    private static final Set<AssignmentStatus> PENDING_PROPOSAL =
            EnumSet.of(AssignmentStatus.PROPOSED, AssignmentStatus.NOTIFIED);

    private final FreightLoadRepository repository;
    private final OutboxEventService outboxEventService;
    private final ProofOfDeliveryMediaRepository podMediaRepository;

    @Value("${truckdar.assignment.max-attempts:5}")
    private int maxAssignmentAttempts = 5;

    @Override
    @Transactional
    public FreightLoadResponse publishLoad(CreateFreightLoadRequest request, UUID correlationId,
                                           String authenticatedUserId, String authenticatedRole) {
        FreightLoad load = FreightLoad.builder()
                .correlationId(correlationId != null ? correlationId : UUID.randomUUID())
                .generatorCompanyId(resolveGeneratorCompany(request, authenticatedUserId, authenticatedRole))
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
        commitmentsOf(request.getDriverId(), loadId).stream().findFirst().ifPresent(other -> {
            throw new InvalidLoadOperationException("El conductor " + request.getDriverId()
                    + " está ocupado con la carga #" + other.getId() + " (" + describeCommitment(other)
                    + "); no puede recibir la carga #" + loadId);
        });
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
        if (!isOpenForAssignment(load)) {
            log.info("Recomendación ignorada para carga #{} con status {}", loadId, load.getStatus());
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
        if (load == null || !isOpenForAssignment(load) || !REASSIGNABLE.contains(load.getAssignmentStatus())) {
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
                || !isOpenForAssignment(load)
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
        if (!isOpenForAssignment(load) || load.getAssignmentStatus() != AssignmentStatus.PROPOSED) {
            log.info("assignment.notified ignorado para carga #{} en estado {}/{}", loadId, load.getStatus(),
                    load.getAssignmentStatus());
            return false;
        }
        transitionAssignment(loadId, AssignmentStatus.NOTIFIED, AssignmentStatus.PROPOSED, "assignment.notified");
        return true;
    }

    @Override
    @Transactional
    public FreightLoadResponse acceptAssignment(Long loadId, String authenticatedUserId) {
        requireAssignedDriver(loadId, authenticatedUserId, "aceptar");
        // Una propuesta pendiente en otra carga no bloquea; una carga aceptada o en ruta sí.
        repository.findByAssignedDriverIdAndStatusIn(authenticatedUserId, DRIVER_COMMITTED).stream()
                .filter(other -> !other.getId().equals(loadId))
                .findFirst()
                .ifPresent(other -> {
                    throw new InvalidLoadOperationException("Ya tiene la carga #" + other.getId() + " en status "
                            + other.getStatus() + "; termínela antes de aceptar la carga #" + loadId);
                });
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
        requireOpenForAssignment(load);
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
        requireOpenForAssignment(load);
        if (load.getAssignmentStatus() != expected) {
            throw new InvalidLoadOperationException("Transición inválida para la carga #" + loadId
                    + ": se esperaba " + expected + " pero el estado actual es " + load.getAssignmentStatus());
        }
        load.setAssignmentStatus(target);
        load.setAssignmentStatusChangedAt(LocalDateTime.now());
        return load;
    }

    /** El ciclo comercial (proponer, notificar, aceptar, vencer) solo aplica mientras la carga está AVAILABLE. */
    private boolean isOpenForAssignment(FreightLoad load) {
        return load.getStatus() == LoadStatus.AVAILABLE;
    }

    private void requireOpenForAssignment(FreightLoad load) {
        if (!isOpenForAssignment(load)) {
            throw new InvalidLoadOperationException("La carga #" + load.getId()
                    + " ya no admite cambios de asignación (status=" + load.getStatus() + ")");
        }
    }

    // ------------------------------------------------------------------ ciclo de vida del viaje

    /** Torre de control (operador logístico): ve y gestiona la operación completa. */
    private static final Set<String> CONTROL_TOWER_ROLES = Set.of("COORDINADOR", "ADMIN");
    /** Empresa generadora de carga: publica cargas y solo ve y gestiona las suyas. */
    private static final String GENERADOR = "GENERADOR";
    private static final Set<String> PUBLISHER_ROLES = Set.of(GENERADOR, "COORDINADOR", "ADMIN");
    private static final Set<LoadStatus> CANCELLABLE = EnumSet.of(LoadStatus.AVAILABLE, LoadStatus.ASSIGNED);

    /** ASSIGNED -> IN_TRANSIT: el conductor asignado inicia el viaje. */
    @Override
    @Transactional
    public FreightLoadResponse startTrip(Long loadId, String authenticatedUserId) {
        requireAssignedDriver(loadId, authenticatedUserId, "iniciar el viaje de");
        FreightLoad load = requireStatus(loadId, LoadStatus.ASSIGNED, "iniciar el viaje");
        load.setStatus(LoadStatus.IN_TRANSIT);
        load.setTripStartedAt(LocalDateTime.now());
        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, "trip.started");
        log.info("Viaje de la carga #{} iniciado por el conductor {}", loadId, authenticatedUserId);
        return mapToResponse(updated);
    }

    /** IN_TRANSIT -> DELIVERED: el conductor asignado registra la entrega con su prueba (POD). */
    @Override
    @Transactional
    public FreightLoadResponse deliverLoad(Long loadId, String authenticatedUserId, DeliveryConfirmationRequest pod) {
        requireAssignedDriver(loadId, authenticatedUserId, "entregar");
        FreightLoad load = requireStatus(loadId, LoadStatus.IN_TRANSIT, "registrar la entrega");
        load.setStatus(LoadStatus.DELIVERED);
        load.setDeliveredAt(LocalDateTime.now());
        load.setPodReceiverName(pod.getReceiverName().trim());
        load.setPodReceiverDocument(pod.getReceiverDocument());
        load.setPodNotes(pod.getNotes());
        boolean hasSignature = StringUtils.hasText(pod.getSignatureDataUrl());
        boolean hasPhoto = StringUtils.hasText(pod.getPhotoUrl());
        load.setPodHasSignature(hasSignature);
        load.setPodHasPhoto(hasPhoto);
        if (hasSignature || hasPhoto) {
            podMediaRepository.save(ProofOfDeliveryMedia.builder()
                    .loadId(loadId)
                    .signatureDataUrl(hasSignature ? pod.getSignatureDataUrl() : null)
                    .photoUrl(hasPhoto ? pod.getPhotoUrl() : null)
                    .createdAt(LocalDateTime.now())
                    .build());
        }
        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, "trip.delivered");
        log.info("Carga #{} entregada a {}", loadId, updated.getPodReceiverName());
        return mapToResponse(updated);
    }

    /**
     * AVAILABLE | ASSIGNED -> CANCELLED antes de iniciar el viaje: torre de control (COORDINADOR o ADMIN) o el
     * GENERADOR dueño de la carga.
     */
    @Override
    @Transactional
    public FreightLoadResponse cancelLoad(Long loadId, String authenticatedUserId, String authenticatedRole,
                                          CancelLoadRequest request) {
        if (authenticatedUserId == null || authenticatedUserId.isBlank()) {
            throw new AssignmentAccessDeniedException("Se requiere un usuario autenticado para cancelar la carga #"
                    + loadId, true);
        }
        FreightLoad load = repository.findById(loadId)
                .orElseThrow(() -> new FreightLoadNotFoundException("No se encontró la carga con ID: " + loadId));
        if (!isControlTower(authenticatedRole) && !isOwningGenerator(load, authenticatedUserId, authenticatedRole)) {
            throw new AssignmentAccessDeniedException("Solo la torre de control o el generador dueño pueden cancelar la carga #"
                    + loadId + " (rol actual: " + authenticatedRole + ")", false);
        }
        if (!CANCELLABLE.contains(load.getStatus())) {
            throw new InvalidLoadOperationException("La carga #" + loadId + " no puede cancelarse en status "
                    + load.getStatus() + "; solo antes de iniciar el viaje");
        }
        load.setStatus(LoadStatus.CANCELLED);
        load.setCancelledAt(LocalDateTime.now());
        load.setCancellationReason(request.getReason().trim());
        load.setCancelledBy(authenticatedUserId);
        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, "load.cancelled");
        log.info("Carga #{} cancelada por {} ({}): {}", loadId, authenticatedUserId, authenticatedRole,
                updated.getCancellationReason());
        return mapToResponse(updated);
    }

    /** Cargas del conductor autenticado: propuestas vigentes, aceptadas y en tránsito. */
    @Override
    @Transactional(readOnly = true)
    public List<FreightLoadResponse> getLoadsForDriver(String authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId.isBlank()) {
            throw new AssignmentAccessDeniedException("Se requiere un usuario autenticado para consultar sus cargas", true);
        }
        return repository.findByAssignedDriverIdOrderByAssignedAtDesc(authenticatedUserId).stream()
                .filter(load -> load.getStatus() == LoadStatus.ASSIGNED
                        || load.getStatus() == LoadStatus.IN_TRANSIT
                        || (load.getStatus() == LoadStatus.AVAILABLE
                            && (load.getAssignmentStatus() == AssignmentStatus.PROPOSED
                                || load.getAssignmentStatus() == AssignmentStatus.NOTIFIED)))
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * POD completo de una carga entregada. Incluye datos personales de quien recibe (documento y firma), así que
     * solo lo ven el conductor que la entregó y la torre de control (COORDINADOR o ADMIN).
     */
    @Override
    @Transactional(readOnly = true)
    public ProofOfDeliveryResponse getProofOfDelivery(Long loadId, String authenticatedUserId, String authenticatedRole) {
        if (authenticatedUserId == null || authenticatedUserId.isBlank()) {
            throw new AssignmentAccessDeniedException("Se requiere un usuario autenticado para ver la prueba de entrega", true);
        }
        FreightLoad load = repository.findById(loadId)
                .orElseThrow(() -> new FreightLoadNotFoundException("No se encontró la carga con ID: " + loadId));
        if (!isControlTower(authenticatedRole) && !authenticatedUserId.equals(load.getAssignedDriverId())
                && !isOwningGenerator(load, authenticatedUserId, authenticatedRole)) {
            throw new AssignmentAccessDeniedException("Solo el conductor de la carga #" + loadId
                    + ", su generador o la torre de control pueden ver su prueba de entrega", false);
        }
        if (load.getStatus() != LoadStatus.DELIVERED) {
            throw new InvalidLoadOperationException("La carga #" + loadId + " no tiene prueba de entrega (status="
                    + load.getStatus() + ")");
        }
        ProofOfDeliveryMedia media = podMediaRepository.findById(loadId).orElse(null);
        return ProofOfDeliveryResponse.builder()
                .loadId(loadId)
                .driverId(load.getAssignedDriverId())
                .driverName(load.getAssignedDriverName())
                .vehiclePlate(load.getAssignedVehiclePlate())
                .tripStartedAt(load.getTripStartedAt())
                .deliveredAt(load.getDeliveredAt())
                .receiverName(load.getPodReceiverName())
                .receiverDocument(load.getPodReceiverDocument())
                .notes(load.getPodNotes())
                .signatureDataUrl(media == null ? null : media.getSignatureDataUrl())
                .photoUrl(media == null ? null : media.getPhotoUrl())
                .build();
    }

    /** Tablero de la torre de control: cargas de todos los generadores, filtradas por status (máx. 200 recientes). */
    @Override
    @Transactional(readOnly = true)
    public List<FreightLoadResponse> listLoads(List<LoadStatus> statuses, String authenticatedUserId,
                                               String authenticatedRole) {
        if (authenticatedUserId == null || authenticatedUserId.isBlank()) {
            throw new AssignmentAccessDeniedException("Se requiere un usuario autenticado para listar cargas", true);
        }
        if (authenticatedRole == null || !CONTROL_TOWER_ROLES.contains(authenticatedRole)) {
            throw new AssignmentAccessDeniedException("Solo un COORDINADOR o ADMIN puede listar todas las cargas", false);
        }
        List<FreightLoad> loads = statuses == null || statuses.isEmpty()
                ? repository.findTop200ByOrderByPublishedAtDesc()
                : repository.findTop200ByStatusInOrderByPublishedAtDesc(statuses);
        return loads.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    /** Conductores ocupados (carga aceptada, en ruta o con propuesta vigente), para excluirlos del matching. */
    @Override
    @Transactional(readOnly = true)
    public List<DriverCommitmentResponse> getDriverCommitments(String authenticatedRole) {
        denyGenerator(authenticatedRole, "la ocupación de los conductores");
        return repository.findDriverCommitments(DRIVER_COMMITTED, LoadStatus.AVAILABLE, PENDING_PROPOSAL).stream()
                .map(load -> DriverCommitmentResponse.builder()
                        .driverId(load.getAssignedDriverId())
                        .loadId(load.getId())
                        .loadStatus(load.getStatus())
                        .assignmentStatus(load.getAssignmentStatus())
                        .build())
                .collect(Collectors.toList());
    }

    /** Compromisos del conductor en cargas distintas de {@code exceptLoadId}. */
    private List<FreightLoad> commitmentsOf(String driverId, Long exceptLoadId) {
        return repository.findDriverCommitments(DRIVER_COMMITTED, LoadStatus.AVAILABLE, PENDING_PROPOSAL).stream()
                .filter(load -> driverId.equals(load.getAssignedDriverId()) && !load.getId().equals(exceptLoadId))
                .collect(Collectors.toList());
    }

    private String describeCommitment(FreightLoad load) {
        return load.getStatus() == LoadStatus.AVAILABLE
                ? "propuesta " + load.getAssignmentStatus()
                : load.getStatus().name();
    }

    private FreightLoad requireStatus(Long loadId, LoadStatus expected, String action) {
        FreightLoad load = repository.findById(loadId)
                .orElseThrow(() -> new FreightLoadNotFoundException("No se encontró la carga con ID: " + loadId));
        if (load.getStatus() != expected) {
            throw new InvalidLoadOperationException("No se puede " + action + " de la carga #" + loadId
                    + ": se esperaba status " + expected + " pero es " + load.getStatus());
        }
        return load;
    }

    @Override
    @Transactional(readOnly = true)
    public FreightLoadResponse getLoadById(Long loadId, String authenticatedUserId, String authenticatedRole) {
        FreightLoad load = repository.findById(loadId)
                .orElseThrow(() -> new FreightLoadNotFoundException("No se encontró la carga con ID: " + loadId));
        // Un generador solo consulta sus propias cargas; conductores, torre y servicios internos no cambian.
        if (GENERADOR.equals(authenticatedRole) && !isOwningGenerator(load, authenticatedUserId, authenticatedRole)) {
            throw new AssignmentAccessDeniedException("La carga #" + loadId + " pertenece a otra empresa generadora", false);
        }
        return mapToResponse(load);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FreightLoadResponse> getAvailableLoads(TruckTypeRequired truckType, String authenticatedRole) {
        denyGenerator(authenticatedRole, "la bolsa de cargas disponibles de todas las empresas");
        List<FreightLoad> results = (truckType != null)
                ? repository.findByStatusAndRequiredTruckType(LoadStatus.AVAILABLE, truckType)
                : repository.findByStatusOrderByPublishedAtDesc(LoadStatus.AVAILABLE);

        return results.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<FreightLoadResponse> getLoadsByCompany(String generatorCompanyId, String authenticatedUserId,
                                                       String authenticatedRole) {
        if (authenticatedUserId == null || authenticatedUserId.isBlank()) {
            throw new AssignmentAccessDeniedException("Se requiere un usuario autenticado para consultar cargas de una empresa", true);
        }
        if (!generatorCompanyId.equals(authenticatedUserId) && !"ADMIN".equals(authenticatedRole)) {
            throw new AssignmentAccessDeniedException("Solo puede consultar las cargas de su propia empresa", false);
        }
        return getLoadsByCompany(generatorCompanyId);
    }

    /** Envíos del generador autenticado (su id de usuario es su generatorCompanyId). */
    @Override
    @Transactional(readOnly = true)
    public List<FreightLoadResponse> getPublishedLoads(String authenticatedUserId, String authenticatedRole) {
        if (authenticatedUserId == null || authenticatedUserId.isBlank()) {
            throw new AssignmentAccessDeniedException("Se requiere un usuario autenticado para consultar sus envíos", true);
        }
        if (authenticatedRole == null || !PUBLISHER_ROLES.contains(authenticatedRole)) {
            throw new AssignmentAccessDeniedException("Solo un GENERADOR, COORDINADOR o ADMIN tiene envíos publicados (rol actual: "
                    + authenticatedRole + ")", false);
        }
        return getLoadsByCompany(authenticatedUserId);
    }

    private boolean isControlTower(String authenticatedRole) {
        return authenticatedRole != null && CONTROL_TOWER_ROLES.contains(authenticatedRole);
    }

    /** El generador es dueño de la carga si la publicó él: su id de usuario es el generatorCompanyId. */
    private boolean isOwningGenerator(FreightLoad load, String authenticatedUserId, String authenticatedRole) {
        return GENERADOR.equals(authenticatedRole) && authenticatedUserId != null
                && authenticatedUserId.equals(load.getGeneratorCompanyId());
    }

    /** Consultas de toda la operación: un GENERADOR no las ve (las llamadas internas sin rol siguen permitidas). */
    private void denyGenerator(String authenticatedRole, String resource) {
        if (GENERADOR.equals(authenticatedRole)) {
            throw new AssignmentAccessDeniedException("Un GENERADOR no tiene acceso a " + resource, false);
        }
    }

    /**
     * La empresa generadora es el usuario que publica (X-Auth-User), no un valor que el cliente pueda elegir.
     * Sin usuario (llamada interna directa, sin gateway) se acepta el generatorCompanyId del cuerpo.
     */
    private String resolveGeneratorCompany(CreateFreightLoadRequest request, String authenticatedUserId,
                                           String authenticatedRole) {
        if (authenticatedUserId != null && !authenticatedUserId.isBlank()) {
            if (authenticatedRole == null || !PUBLISHER_ROLES.contains(authenticatedRole)) {
                throw new AssignmentAccessDeniedException("Solo un GENERADOR, COORDINADOR o ADMIN puede publicar cargas (rol actual: "
                        + authenticatedRole + ")", false);
            }
            return authenticatedUserId;
        }
        if (request.getGeneratorCompanyId() == null || request.getGeneratorCompanyId().isBlank()) {
            throw new AssignmentAccessDeniedException(
                    "Se requiere un usuario autenticado (o generatorCompanyId en llamadas internas) para publicar", true);
        }
        return request.getGeneratorCompanyId();
    }

    private List<FreightLoadResponse> getLoadsByCompany(String generatorCompanyId) {
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
                .tripStartedAt(entity.getTripStartedAt())
                .deliveredAt(entity.getDeliveredAt())
                .podReceiverName(entity.getPodReceiverName())
                .podReceiverDocument(entity.getPodReceiverDocument())
                .podNotes(entity.getPodNotes())
                .podHasSignature(Boolean.TRUE.equals(entity.getPodHasSignature()))
                .podHasPhoto(Boolean.TRUE.equals(entity.getPodHasPhoto()))
                .cancelledAt(entity.getCancelledAt())
                .cancellationReason(entity.getCancellationReason())
                .cancelledBy(entity.getCancelledBy())
                .build();
    }
}
