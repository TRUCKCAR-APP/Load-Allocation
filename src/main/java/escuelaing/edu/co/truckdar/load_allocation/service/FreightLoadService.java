package escuelaing.edu.co.truckdar.load_allocation.service;

import escuelaing.edu.co.truckdar.load_allocation.dto.request.AssignDriverRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.request.CreateFreightLoadRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.response.FreightLoadResponse;
import escuelaing.edu.co.truckdar.load_allocation.exception.FreightLoadNotFoundException;
import escuelaing.edu.co.truckdar.load_allocation.exception.InvalidLoadOperationException;
import escuelaing.edu.co.truckdar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckdar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckdar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckdar.load_allocation.model.TruckTypeRequired;
import escuelaing.edu.co.truckdar.load_allocation.repository.FreightLoadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FreightLoadService implements IFreightLoadService {

    private final FreightLoadRepository repository;
    private final OutboxEventService outboxEventService;

    @Override
    @Transactional
    public FreightLoadResponse publishLoad(CreateFreightLoadRequest request) {
        FreightLoad load = FreightLoad.builder()
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

        if (load.getStatus() != LoadStatus.AVAILABLE || load.getAssignmentStatus() != null) {
            throw new InvalidLoadOperationException("La carga #" + loadId + " no está disponible para asignación (estado actual: " + load.getStatus() + ")");
        }

        load.setAssignedDriverId(request.getDriverId());
        load.setAssignedDriverName(request.getDriverName());
        load.setAssignedVehiclePlate(request.getVehiclePlate());
        load.setAssignmentStatus(AssignmentStatus.PROPOSED);
        load.setAssignmentStatusChangedAt(LocalDateTime.now());

        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, "assignment.proposed");
        log.info("Propuesta de asignación creada para carga #{} y conductor {}", updated.getId(), request.getDriverName());

        return mapToResponse(updated);
    }

    @Override
    @Transactional
    public FreightLoadResponse notifyAssignment(Long loadId) {
        return transitionAssignment(loadId, AssignmentStatus.NOTIFIED, AssignmentStatus.PROPOSED,
                "assignment.notified");
    }

    @Override
    @Transactional
    public FreightLoadResponse acceptAssignment(Long loadId) {
        FreightLoad load = transition(loadId, AssignmentStatus.ACCEPTED, AssignmentStatus.NOTIFIED);
        load.setStatus(LoadStatus.ASSIGNED);
        load.setAssignedAt(LocalDateTime.now());
        FreightLoad updated = repository.save(load);
        outboxEventService.enqueueLoadEvent(updated, "assignment.accepted");
        return mapToResponse(updated);
    }

    @Override
    @Transactional
    public FreightLoadResponse rejectAssignment(Long loadId) {
        return transitionAssignment(loadId, AssignmentStatus.REJECTED, AssignmentStatus.NOTIFIED,
                "assignment.rejected");
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
                .build();
    }
}