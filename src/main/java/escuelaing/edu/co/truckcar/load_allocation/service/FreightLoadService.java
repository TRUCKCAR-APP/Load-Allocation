package escuelaing.edu.co.truckcar.load_allocation.service;

import escuelaing.edu.co.truckcar.load_allocation.dto.request.AssignDriverRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.CancelLoadRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.CreateFreightLoadRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.DeliveryConfirmationRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.PodObjectReference;
import escuelaing.edu.co.truckcar.load_allocation.storage.PodMediaKind;
import escuelaing.edu.co.truckcar.load_allocation.dto.response.DriverCommitmentResponse;
import escuelaing.edu.co.truckcar.load_allocation.dto.response.FreightLoadResponse;
import escuelaing.edu.co.truckcar.load_allocation.dto.response.ProofOfDeliveryResponse;
import escuelaing.edu.co.truckcar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckcar.load_allocation.exception.FreightLoadNotFoundException;
import escuelaing.edu.co.truckcar.load_allocation.exception.InvalidLoadOperationException;
import escuelaing.edu.co.truckcar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckcar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.ProofOfDeliveryMedia;
import escuelaing.edu.co.truckcar.load_allocation.model.TruckTypeRequired;
import escuelaing.edu.co.truckcar.load_allocation.repository.FreightLoadRepository;
import escuelaing.edu.co.truckcar.load_allocation.repository.ProofOfDeliveryMediaRepository;
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
    /** Además de lo que reasigna el motor, la torre puede reabrir una carga que se quedó sin conductores. */
    private static final Set<AssignmentStatus> TOWER_REASSIGNABLE = EnumSet.of(AssignmentStatus.REJECTED,
            AssignmentStatus.TIMEOUT, AssignmentStatus.UNASSIGNED_NO_DRIVERS);
    /** Un conductor con una carga en estos status está ocupado: no puede aceptar ni recibir otra. */
    private static final Set<LoadStatus> DRIVER_COMMITTED = EnumSet.of(LoadStatus.ASSIGNED, LoadStatus.IN_TRANSIT);
    /** Propuesta vigente: el conductor debe responderla antes de recibir otra. */
    private static final Set<AssignmentStatus> PENDING_PROPOSAL =
            EnumSet.of(AssignmentStatus.PROPOSED, AssignmentStatus.NOTIFIED);

    private final FreightLoadRepository repository;
    private final OutboxEventService outboxEventService;
    private final ProofOfDeliveryMediaRepository podMediaRepository;

    /** Afiliación de cada conductor (carga propia: solo sus conductores). Opcional para no tocar las pruebas. */
    private escuelaing.edu.co.truckcar.load_allocation.repository.DriverAffiliationRepository affiliations;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAffiliations(escuelaing.edu.co.truckcar.load_allocation.repository.DriverAffiliationRepository affiliations) {
        this.affiliations = affiliations;
    }

    @Value("${truckcar.assignment.max-attempts:5}")
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

    /**
     * Carga propia de una transportadora (empresa ya confirmada en User-Identity): la publica su cuenta (dueña como un
     * generador: la ve y la cancela) y solo se propone a sus conductores con la afiliación ACTIVA.
     */
    @Transactional
    public FreightLoadResponse publishOwnLoad(CreateFreightLoadRequest request, UUID correlationId, String ownerUserId,
                                              String companyId, String companyName) {
        FreightLoad load = FreightLoad.builder()
                .correlationId(correlationId != null ? correlationId : UUID.randomUUID())
                .generatorCompanyId(ownerUserId)
                .generatorCompanyName(companyName != null && !companyName.isBlank() ? companyName : request.getGeneratorCompanyName())
                .ownerCompanyId(companyId)
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
        log.info("Carga propia #{} publicada por la transportadora {} ({} Tn)", saved.getId(), companyId, saved.getWeightTons());
        return mapToResponse(saved);
    }

    /** Las propias y las que llevan (o llevaron) sus conductores con la afiliación ACTIVA; las 200 más recientes. */
    @Transactional(readOnly = true)
    public List<FreightLoadResponse> companyFleetLoads(String companyId) {
        return repository.findCompanyFleetLoads(companyId, activeDriversOf(companyId),
                        org.springframework.data.domain.PageRequest.of(0, 200))
                .stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    /** Prueba de entrega de una carga de la flota de la empresa (propia o de uno de sus conductores). */
    @Transactional(readOnly = true)
    public ProofOfDeliveryResponse companyProofOfDelivery(Long loadId, String companyId) {
        FreightLoad load = repository.findById(loadId)
                .orElseThrow(() -> new FreightLoadNotFoundException("No se encontró la carga con ID: " + loadId));
        boolean own = companyId.equals(load.getOwnerCompanyId());
        if (!own && !activeDriversOf(companyId).contains(load.getAssignedDriverId())) {
            throw new AssignmentAccessDeniedException("La carga #" + loadId + " no es de la flota de su empresa", false);
        }
        // Acceso ya comprobado para la empresa: se lee como la torre (mismo contenido que ve el generador).
        return getProofOfDelivery(loadId, load.getAssignedDriverId() != null ? load.getAssignedDriverId() : "empresa", "COORDINADOR");
    }

    private Set<String> activeDriversOf(String companyId) {
        Set<String> drivers = new java.util.HashSet<>();
        if (affiliations != null) {
            affiliations.findByCompanyIdAndAffiliationStatus(companyId, "ACTIVA")
                    .forEach(affiliation -> drivers.add(affiliation.getDriverId()));
        }
        // IN () vacío no es JPQL válido en todos los motores: un valor que no es un id de conductor.
        if (drivers.isEmpty()) {
            drivers.add("-");
        }
        return drivers;
    }

    /** Carga propia: solo conductores de esa empresa con la afiliación ACTIVA (motor y torre por igual). */
    private void requireOwnCompanyDriver(FreightLoad load, String driverId) {
        if (load.getOwnerCompanyId() == null) {
            return;
        }
        boolean member = affiliations != null && affiliations.findById(driverId)
                .filter(a -> load.getOwnerCompanyId().equals(a.getCompanyId()) && "ACTIVA".equals(a.getAffiliationStatus()))
                .isPresent();
        if (!member) {
            throw new InvalidLoadOperationException("La carga #" + load.getId() + " es propia de "
                    + (load.getGeneratorCompanyName() != null ? load.getGeneratorCompanyName() : "una transportadora")
                    + ": solo la pueden llevar sus conductores afiliados");
        }
    }

    @Override
    @Transactional
    public FreightLoadResponse assignDriver(Long loadId, AssignDriverRequest request) {
        return assignDriver(loadId, request, "SYSTEM");
    }

    @Override
    @Transactional
    public FreightLoadResponse assignDriver(Long loadId, AssignDriverRequest request, String authenticatedRole) {
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

        FreightLoad updated = propose(load, request.getDriverId(), request.getDriverName(), request.getVehiclePlate(),
                isControlTower(authenticatedRole) ? PROPOSED_BY_TOWER : PROPOSED_BY_ENGINE);
        log.info("Propuesta de asignación creada para carga #{} y conductor {}", updated.getId(), request.getDriverName());

        return mapToResponse(updated);
    }

    /**
     * La torre de control elige al conductor de una carga aunque ya haya pasado por rechazos o vencimientos
     * (assignDriver solo admite cargas sin propuesta), o le quita una carga aceptada a su conductor antes de que salga.
     * <ul>
     *   <li>AVAILABLE sin propuesta, REJECTED, TIMEOUT o UNASSIGNED_NO_DRIVERS: se propone al conductor elegido.</li>
     *   <li>ASSIGNED (aceptada, sin iniciar el viaje): el conductor actual queda descartado y se emite
     *   assignment.revoked con él (fleets-drivers lo libera y Notifications le avisa); después se propone al nuevo.</li>
     * </ul>
     * Con una propuesta vigente (PROPOSED/NOTIFIED) no: el conductor podría estar aceptándola en ese momento. La
     * propuesta sale por propose(), así que Notifications avisa al nuevo conductor y la carga pasa sola a NOTIFIED.
     */
    @Override
    @Transactional
    public FreightLoadResponse reassignLoad(Long loadId, String authenticatedUserId, String authenticatedRole,
                                            AssignDriverRequest request) {
        if (!isControlTower(authenticatedRole)) {
            boolean anonymous = authenticatedRole == null || authenticatedRole.isBlank();
            throw new AssignmentAccessDeniedException("Solo la torre de control (COORDINADOR o ADMIN) puede reasignar la carga #"
                    + loadId + (anonymous ? "" : " (rol actual: " + authenticatedRole + ")"), anonymous);
        }
        FreightLoad load = repository.findById(loadId)
                .orElseThrow(() -> new FreightLoadNotFoundException("No se encontró la carga con ID: " + loadId));
        String driverId = request.getDriverId().trim();
        boolean accepted = load.getStatus() == LoadStatus.ASSIGNED
                && load.getAssignmentStatus() == AssignmentStatus.ACCEPTED;
        boolean open = load.getStatus() == LoadStatus.AVAILABLE
                && (load.getAssignmentStatus() == null || TOWER_REASSIGNABLE.contains(load.getAssignmentStatus()));
        if (!accepted && !open) {
            throw new InvalidLoadOperationException("La carga #" + loadId + " no se puede reasignar: " + (load.getStatus() == LoadStatus.AVAILABLE
                    ? "tiene una propuesta vigente (" + load.getAssignmentStatus() + ") para " + load.getAssignedDriverName()
                      + "; espere a que la responda o venza"
                    : "está en status " + load.getStatus() + " y solo se reasigna antes de iniciar el viaje"));
        }
        if (accepted && driverId.equals(load.getAssignedDriverId())) {
            throw new InvalidLoadOperationException("El conductor " + driverId + " ya tiene aceptada la carga #" + loadId);
        }
        if (load.getDiscardedDriverIds().contains(driverId)) {
            throw new InvalidLoadOperationException("El conductor " + driverId
                    + " ya rechazó, dejó vencer o le quitaron la carga #" + loadId);
        }
        commitmentsOf(driverId, loadId).stream().findFirst().ifPresent(other -> {
            throw new InvalidLoadOperationException("El conductor " + driverId + " está ocupado con la carga #"
                    + other.getId() + " (" + describeCommitment(other) + "); no puede recibir la carga #" + loadId);
        });

        if (accepted) {
            String previousDriver = load.getAssignedDriverId();
            load.setStatus(LoadStatus.AVAILABLE);
            load.setAssignedAt(null);
            discardCurrentDriver(load);
            // El evento sale con el conductor anterior todavía puesto: es a quien hay que liberar y avisar.
            outboxEventService.enqueueLoadEvent(load, "assignment.revoked");
            log.info("Carga #{}: la torre ({}) se la quitó al conductor {}", loadId, authenticatedUserId, previousDriver);
        } else if (load.getAssignmentStatus() == AssignmentStatus.UNASSIGNED_NO_DRIVERS) {
            // Nuevo ciclo de asignación, como en assignDriver: se reinicia el límite de intentos del motor.
            load.setAssignmentAttempts(0);
        }
        FreightLoad updated = propose(load, driverId, request.getDriverName(), request.getVehiclePlate(), PROPOSED_BY_TOWER);
        log.info("Carga #{} reasignada por la torre ({}) al conductor {}", loadId, authenticatedUserId, driverId);
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
        propose(load, driverId, driverName, vehiclePlate, PROPOSED_BY_ENGINE);
        log.info("Carga #{} reasignada al conductor {} (intento {}/{})",
                loadId, driverId, load.getAssignmentAttempts(), maxAssignmentAttempts);
        return true;
    }

    @Override
    @Transactional
    public boolean markNoDriversAvailable(Long loadId) {
        FreightLoad load = repository.findById(loadId).orElse(null);
        // También una carga nunca propuesta (assignmentStatus null): el motor la aplaza cuando no hay candidatos o
        // cuando fleets-drivers no responde, y queda visible como UNASSIGNED_NO_DRIVERS hasta el siguiente reintento.
        boolean waitingForDriver = load != null
                && (load.getAssignmentStatus() == null || REASSIGNABLE.contains(load.getAssignmentStatus()));
        if (load == null || !isOpenForAssignment(load) || !waitingForDriver) {
            log.info("Aviso de falta de candidatos ignorado para carga #{}", loadId);
            return false;
        }
        markUnassigned(load, "el motor de asignación no encontró más candidatos");
        return true;
    }

    /**
     * Vence la propuesta solo si sigue PROPOSED o NOTIFIED desde antes del corte (evita expirar una propuesta recién
     * renovada). PROPOSED también vence: si Notifications nunca confirma el aviso, la carga quedaba propuesta para
     * siempre (el conductor no puede aceptarla y el motor no busca a otro) y el flujo dependía de una intervención
     * manual (PATCH /assignment/timeout).
     */
    @Override
    @Transactional
    public boolean expireIfUnanswered(Long loadId, LocalDateTime notifiedBefore) {
        FreightLoad load = repository.findById(loadId).orElse(null);
        if (load == null
                || !isOpenForAssignment(load)
                || !PENDING_PROPOSAL.contains(load.getAssignmentStatus())
                || load.getAssignmentStatusChangedAt() == null
                || !load.getAssignmentStatusChangedAt().isBefore(notifiedBefore)) {
            return false;
        }
        boolean wasNotified = load.getAssignmentStatus() == AssignmentStatus.NOTIFIED;
        discardCurrentDriver(load);
        load.setAssignmentStatus(AssignmentStatus.TIMEOUT);
        load.setAssignmentStatusChangedAt(LocalDateTime.now());
        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, "assignment.timeout");
        AssignmentMetrics.countOutcome("timeout");
        log.info("Propuesta de la carga #{} venció sin respuesta del conductor {} (estaba {})", loadId,
                load.getAssignedDriverId(), wasNotified ? AssignmentStatus.NOTIFIED : AssignmentStatus.PROPOSED);
        return true;
    }

    private FreightLoad propose(FreightLoad load, String driverId, String driverName, String vehiclePlate,
                                String proposedBy) {
        requireOwnCompanyDriver(load, driverId);
        load.setProposedBy(proposedBy);
        load.setAssignedDriverId(driverId);
        load.setAssignedDriverName(driverName);
        load.setAssignedVehiclePlate(vehiclePlate);
        load.setAssignmentAttempts(attemptsOf(load) + 1);
        load.setAssignmentStatus(AssignmentStatus.PROPOSED);
        load.setAssignmentStatusChangedAt(LocalDateTime.now());
        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, "assignment.proposed");
        AssignmentMetrics.countOutcome("proposed");
        if (updated.getAssignmentAttempts() == 1
                && (updated.getDiscardedDriverIds() == null || updated.getDiscardedDriverIds().isEmpty())) {
            AssignmentMetrics.recordLatency("proposed", updated.getPublishedAt());
        }
        return updated;
    }

    private void markUnassigned(FreightLoad load, String reason) {
        load.setAssignmentStatus(AssignmentStatus.UNASSIGNED_NO_DRIVERS);
        load.setAssignmentStatusChangedAt(LocalDateTime.now());
        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, "assignment.unassigned");
        AssignmentMetrics.countOutcome("unassigned");
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
        AssignmentMetrics.countOutcome("accepted");
        AssignmentMetrics.recordLatency("accepted", updated.getPublishedAt());
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
        AssignmentMetrics.countOutcome("rejected");
        return mapToResponse(updated);
    }

    /**
     * Solo el conductor propuesto puede aceptar o rechazar su carga. El api-gateway propaga el sujeto
     * del JWT en X-Auth-User (y descarta cualquier valor enviado por el cliente).
     */
    private FreightLoad requireAssignedDriver(Long loadId, String authenticatedUserId, String action) {
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
        return load;
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
        AssignmentMetrics.countOutcome("timeout");
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
    static final String PROPOSED_BY_TOWER = "TORRE";
    static final String PROPOSED_BY_ENGINE = "MOTOR";
    private static final String CONDUCTOR = "CONDUCTOR";
    private static final Set<String> PUBLISHER_ROLES = Set.of(GENERADOR, "COORDINADOR", "ADMIN");
    private static final Set<LoadStatus> CANCELLABLE = EnumSet.of(LoadStatus.AVAILABLE, LoadStatus.ASSIGNED);

    /** ASSIGNED -> IN_TRANSIT: el conductor asignado inicia el viaje. */
    @Override
    @Transactional
    public FreightLoadResponse startTrip(Long loadId, String authenticatedUserId) {
        return startTrip(loadId, authenticatedUserId, LocalDateTime.now());
    }

    /** startedAt: hora del dispositivo cuando el inicio se registró sin señal y llega en un lote offline. */
    @Override
    @Transactional
    public FreightLoadResponse startTrip(Long loadId, String authenticatedUserId, LocalDateTime startedAt) {
        requireAssignedDriver(loadId, authenticatedUserId, "iniciar el viaje de");
        FreightLoad load = requireStatus(loadId, LoadStatus.ASSIGNED, "iniciar el viaje");
        load.setStatus(LoadStatus.IN_TRANSIT);
        LocalDateTime assignedOrPublished = load.getAssignedAt() != null ? load.getAssignedAt() : load.getPublishedAt();
        load.setTripStartedAt(withinTimeline(startedAt, assignedOrPublished, loadId, "salida"));
        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, "trip.started");
        log.info("Viaje de la carga #{} iniciado por el conductor {}", loadId, authenticatedUserId);
        return mapToResponse(updated);
    }

    /**
     * IN_TRANSIT: el conductor llegó al punto de cargue. Idempotente (un reenvío no cambia la hora registrada).
     */
    @Override
    @Transactional
    public FreightLoadResponse arriveAtPickup(Long loadId, String authenticatedUserId, LocalDateTime arrivedAt) {
        requireAssignedDriver(loadId, authenticatedUserId, "registrar la llegada al cargue de");
        FreightLoad load = requireStatus(loadId, LoadStatus.IN_TRANSIT, "registrar la llegada al cargue");
        if (load.getArrivedAtPickupAt() != null) {
            return mapToResponse(load);
        }
        load.setArrivedAtPickupAt(withinTimeline(arrivedAt, load.getTripStartedAt(), loadId, "llegada al cargue"));
        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, "trip.pickup_arrived");
        log.info("Conductor {} en el punto de cargue de la carga #{}", authenticatedUserId, loadId);
        return mapToResponse(updated);
    }

    /**
     * IN_TRANSIT: cargó y sale hacia el destino. Si no marcó la llegada (sin señal o la omitió) se registra a la misma
     * hora. Idempotente.
     */
    @Override
    @Transactional
    public FreightLoadResponse completePickup(Long loadId, String authenticatedUserId, LocalDateTime pickedUpAt) {
        requireAssignedDriver(loadId, authenticatedUserId, "registrar la recogida de");
        FreightLoad load = requireStatus(loadId, LoadStatus.IN_TRANSIT, "registrar la recogida");
        if (load.getPickedUpAt() != null) {
            return mapToResponse(load);
        }
        LocalDateTime notBefore = load.getArrivedAtPickupAt() != null ? load.getArrivedAtPickupAt() : load.getTripStartedAt();
        LocalDateTime at = withinTimeline(pickedUpAt, notBefore, loadId, "recogida");
        if (load.getArrivedAtPickupAt() == null) {
            load.setArrivedAtPickupAt(at);
        }
        load.setPickedUpAt(at);
        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, "trip.picked_up");
        log.info("Carga #{} recogida por el conductor {}", loadId, authenticatedUserId);
        return mapToResponse(updated);
    }

    /**
     * La hora que reporta el teléfono en un lote offline se acota entre el hito anterior y ahora: un reloj adelantado o
     * atrasado dejaba salidas en el futuro o anteriores a la asignación, y el seguimiento público mostraba la cronología
     * al revés. Dentro de ese rango se respeta la hora del dispositivo.
     */
    static LocalDateTime withinTimeline(LocalDateTime reported, LocalDateTime notBefore, LocalDateTime now) {
        if (reported == null || reported.isAfter(now)) {
            return now;
        }
        if (notBefore != null && reported.isBefore(notBefore)) {
            return notBefore.isAfter(now) ? now : notBefore;
        }
        return reported;
    }

    private LocalDateTime withinTimeline(LocalDateTime reported, LocalDateTime notBefore, Long loadId, String milestone) {
        LocalDateTime accepted = withinTimeline(reported, notBefore, LocalDateTime.now());
        if (reported != null && !accepted.equals(reported)) {
            log.warn("Hora de {} de la carga #{} fuera de la cronología ({}); se registra {}", milestone, loadId, reported, accepted);
        }
        return accepted;
    }

    /** IN_TRANSIT -> DELIVERED: el conductor asignado registra la entrega con su prueba (POD). */
    @Override
    @Transactional
    public FreightLoadResponse deliverLoad(Long loadId, String authenticatedUserId, DeliveryConfirmationRequest pod) {
        return deliverLoad(loadId, authenticatedUserId, pod, LocalDateTime.now());
    }

    /**
     * La firma y la foto llegan como referencias a objetos ya subidos y verificados (ProofOfDeliveryService); aquí se
     * comprueba además que cada clave pertenezca a esta carga. deliveredAt: hora del dispositivo en entregas offline.
     */
    @Override
    @Transactional
    public FreightLoadResponse deliverLoad(Long loadId, String authenticatedUserId, DeliveryConfirmationRequest pod,
                                           LocalDateTime deliveredAt) {
        requireAssignedDriver(loadId, authenticatedUserId, "entregar");
        FreightLoad load = requireStatus(loadId, LoadStatus.IN_TRANSIT, "registrar la entrega");
        if (load.getPickedUpAt() == null) {
            throw new InvalidLoadOperationException("No se puede registrar la entrega de la carga #" + loadId
                    + ": primero registre que la recogió en el punto de cargue");
        }
        PodObjectReference signature = pod.getSignature();
        PodObjectReference photo = pod.getPhoto();
        requireOwnedKey(loadId, PodMediaKind.SIGNATURE, signature);
        requireOwnedKey(loadId, PodMediaKind.PHOTO, photo);
        load.setStatus(LoadStatus.DELIVERED);
        load.setDeliveredAt(withinTimeline(deliveredAt, load.getPickedUpAt(), loadId, "entrega"));
        load.setPodReceiverName(pod.getReceiverName().trim());
        load.setPodReceiverDocument(pod.getReceiverDocument());
        load.setPodNotes(pod.getNotes());
        load.setPodHasSignature(signature != null);
        load.setPodHasPhoto(photo != null);
        if (signature != null || photo != null) {
            podMediaRepository.save(ProofOfDeliveryMedia.builder()
                    .loadId(loadId)
                    .signatureObjectKey(signature == null ? null : signature.getObjectKey())
                    .signatureSha256(signature == null ? null : signature.getSha256())
                    .signatureContentType(signature == null ? null : signature.getContentType())
                    .signatureSizeBytes(signature == null ? null : signature.getSizeBytes())
                    .photoObjectKey(photo == null ? null : photo.getObjectKey())
                    .photoSha256(photo == null ? null : photo.getSha256())
                    .photoContentType(photo == null ? null : photo.getContentType())
                    .photoSizeBytes(photo == null ? null : photo.getSizeBytes())
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
        // El evento sale con el conductor y el estado de la asignación de este momento: fleets-drivers lo usa para
        // liberar al conductor y Notifications para avisarle. Después se limpia la asignación, que ya no existe: antes
        // la carga quedaba CANCELLED con assignmentStatus ACCEPTED y el conductor puesto, un dato contradictorio.
        outboxEventService.enqueueLoadEvent(load, "load.cancelled");
        releaseAssignment(load);
        FreightLoad updated = repository.save(load);
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
                .signatureObjectKey(media == null ? null : media.getSignatureObjectKey())
                .signatureSha256(media == null ? null : media.getSignatureSha256())
                .photoObjectKey(media == null ? null : media.getPhotoObjectKey())
                .photoSha256(media == null ? null : media.getPhotoSha256())
                // Fotos previas al almacenamiento de objetos que eran un enlace https: se devuelven tal cual.
                .photoUrl(media == null ? null : media.getPhotoExternalUrl())
                .build();
    }

    /** Carga del conductor autenticado lista para recibir evidencias de entrega (ASSIGNED o IN_TRANSIT). */
    @Override
    @Transactional(readOnly = true)
    public void requireDriverCanUploadProof(Long loadId, String authenticatedUserId) {
        FreightLoad load = requireAssignedDriver(loadId, authenticatedUserId, "subir la prueba de entrega de");
        if (load.getStatus() != LoadStatus.ASSIGNED && load.getStatus() != LoadStatus.IN_TRANSIT) {
            throw new InvalidLoadOperationException("La carga #" + loadId + " no admite evidencias de entrega (status="
                    + load.getStatus() + ")");
        }
    }

    /**
     * Mismas comprobaciones de identidad y estado que deliverLoad, pero antes de verificar las evidencias en el
     * almacenamiento: entregar sin haber iniciado el viaje es un 409 claro, no un 422 por la evidencia.
     */
    @Override
    @Transactional(readOnly = true)
    public void requireDriverCanDeliver(Long loadId, String authenticatedUserId) {
        requireAssignedDriver(loadId, authenticatedUserId, "entregar");
        requireStatus(loadId, LoadStatus.IN_TRANSIT, "registrar la entrega");
    }

    private static void requireOwnedKey(Long loadId, PodMediaKind kind, PodObjectReference reference) {
        if (reference != null && !kind.ownsKey(loadId, reference.getObjectKey())) {
            throw new InvalidLoadOperationException("El archivo " + reference.getObjectKey() + " no es una evidencia "
                    + kind + " de la carga #" + loadId);
        }
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

    /**
     * Quién puede leer una carga (Lote 2): la torre de control, el motor (SYSTEM), los servicios internos (sin
     * identidad: por el gateway esta ruta siempre lleva X-Auth-User), el generador dueño y el conductor que la tiene o
     * al que se le ofreció (los descartados: la rechazó, la dejó vencer, se la quitaron o se canceló teniéndola, para
     * que su app pueda mostrar qué pasó). Ningún otro conductor lee una carga ajena con su presupuesto.
     */
    private void requireReadAccess(FreightLoad load, String authenticatedUserId, String authenticatedRole) {
        boolean anonymous = (authenticatedUserId == null || authenticatedUserId.isBlank())
                && (authenticatedRole == null || authenticatedRole.isBlank());
        if (anonymous || isControlTower(authenticatedRole) || "SYSTEM".equals(authenticatedRole)) {
            return;
        }
        if (GENERADOR.equals(authenticatedRole)) {
            if (isOwningGenerator(load, authenticatedUserId, authenticatedRole)) {
                return;
            }
            throw new AssignmentAccessDeniedException("La carga #" + load.getId() + " pertenece a otra empresa generadora", false);
        }
        // La transportadora ve sus cargas propias aquí; las de su flota, en /loads/fleet (empresa confirmada).
        if (isOwningGenerator(load, authenticatedUserId, authenticatedRole)) {
            return;
        }
        if (CONDUCTOR.equals(authenticatedRole) && authenticatedUserId != null
                && (authenticatedUserId.equals(load.getAssignedDriverId())
                    || load.getDiscardedDriverIds().contains(authenticatedUserId))) {
            return;
        }
        throw new AssignmentAccessDeniedException("No tiene acceso a la carga #" + load.getId()
                + ": solo la ve el conductor al que se le asignó u ofreció, el generador dueño y la torre de control", false);
    }

    /**
     * Una carga cancelada no tiene asignación: ni propuesta vigente ni conductor. Quien la tenía queda entre los
     * descartados, para que pueda seguir leyéndola y su app le muestre que se canceló.
     */
    private static void releaseAssignment(FreightLoad load) {
        if (load.getAssignmentStatus() == null && load.getAssignedDriverId() == null) {
            return;
        }
        if (load.getAssignedDriverId() != null) {
            load.getDiscardedDriverIds().add(load.getAssignedDriverId());
        }
        load.setAssignmentStatus(null);
        load.setAssignmentStatusChangedAt(LocalDateTime.now());
        load.setAssignedDriverId(null);
        load.setAssignedDriverName(null);
        load.setAssignedVehiclePlate(null);
    }

    @Override
    @Transactional(readOnly = true)
    public FreightLoadResponse getLoadById(Long loadId, String authenticatedUserId, String authenticatedRole) {
        FreightLoad load = repository.findById(loadId)
                .orElseThrow(() -> new FreightLoadNotFoundException("No se encontró la carga con ID: " + loadId));
        requireReadAccess(load, authenticatedUserId, authenticatedRole);
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
        boolean publisher = GENERADOR.equals(authenticatedRole)
                || ("TRANSPORTADORA".equals(authenticatedRole) && load.getOwnerCompanyId() != null);
        return publisher && authenticatedUserId != null
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
                .arrivedAtPickupAt(entity.getArrivedAtPickupAt())
                .pickedUpAt(entity.getPickedUpAt())
                .deliveredAt(entity.getDeliveredAt())
                .podReceiverName(entity.getPodReceiverName())
                .podReceiverDocument(entity.getPodReceiverDocument())
                .podNotes(entity.getPodNotes())
                .podHasSignature(Boolean.TRUE.equals(entity.getPodHasSignature()))
                .podHasPhoto(Boolean.TRUE.equals(entity.getPodHasPhoto()))
                .ownerCompanyId(entity.getOwnerCompanyId())
                .cancelledAt(entity.getCancelledAt())
                .cancellationReason(entity.getCancellationReason())
                .cancelledBy(entity.getCancelledBy())
                .build();
    }
}
