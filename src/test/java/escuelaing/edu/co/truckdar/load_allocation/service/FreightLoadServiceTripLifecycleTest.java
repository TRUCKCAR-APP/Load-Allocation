package escuelaing.edu.co.truckdar.load_allocation.service;

import escuelaing.edu.co.truckdar.load_allocation.dto.request.CancelLoadRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.request.DeliveryConfirmationRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.response.FreightLoadResponse;
import escuelaing.edu.co.truckdar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckdar.load_allocation.exception.InvalidLoadOperationException;
import escuelaing.edu.co.truckdar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckdar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckdar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckdar.load_allocation.repository.FreightLoadRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FreightLoadServiceTripLifecycleTest {

    private static final String DRIVER = "driver-ana";

    @Mock
    private FreightLoadRepository repository;

    @Mock
    private OutboxEventService outboxEventService;

    private FreightLoadService serviceWith(FreightLoad load) {
        when(repository.findById(load.getId())).thenReturn(Optional.of(load));
        when(repository.save(any(FreightLoad.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return new FreightLoadService(repository, outboxEventService);
    }

    private FreightLoad load(LoadStatus status, AssignmentStatus assignment) {
        return FreightLoad.builder()
                .id(30L)
                .status(status)
                .assignmentStatus(assignment)
                .assignedDriverId(DRIVER)
                .build();
    }

    private DeliveryConfirmationRequest pod() {
        return DeliveryConfirmationRequest.builder()
                .receiverName("  Jefe de Bodega Cali  ")
                .receiverDocument("CC 1020304050")
                .notes("Sin novedades")
                .build();
    }

    @Test
    void assignedDriverStartsTripAndDeliversWithProofOfDelivery() {
        FreightLoad load = load(LoadStatus.ASSIGNED, AssignmentStatus.ACCEPTED);
        FreightLoadService service = serviceWith(load);

        service.startTrip(30L, DRIVER);
        assertThat(load.getStatus()).isEqualTo(LoadStatus.IN_TRANSIT);
        assertThat(load.getTripStartedAt()).isNotNull();

        FreightLoadResponse delivered = service.deliverLoad(30L, DRIVER, pod());
        assertThat(delivered.getStatus()).isEqualTo(LoadStatus.DELIVERED);
        assertThat(delivered.getPodReceiverName()).isEqualTo("Jefe de Bodega Cali");
        assertThat(delivered.getPodReceiverDocument()).isEqualTo("CC 1020304050");
        assertThat(delivered.getDeliveredAt()).isNotNull();

        verify(outboxEventService).enqueueLoadEvent(load, "trip.started");
        verify(outboxEventService).enqueueLoadEvent(load, "trip.delivered");
    }

    @Test
    void onlyTheAssignedDriverCanStartOrDeliverTheTrip() {
        FreightLoad load = load(LoadStatus.ASSIGNED, AssignmentStatus.ACCEPTED);
        FreightLoadService service = serviceWith(load);

        assertThatThrownBy(() -> service.startTrip(30L, "otro-conductor"))
                .isInstanceOf(AssignmentAccessDeniedException.class);
        assertThatThrownBy(() -> service.startTrip(30L, null))
                .isInstanceOf(AssignmentAccessDeniedException.class)
                .satisfies(e -> assertThat(((AssignmentAccessDeniedException) e).isUnauthenticated()).isTrue());
        assertThat(load.getStatus()).isEqualTo(LoadStatus.ASSIGNED);
    }

    @Test
    void tripTransitionsRequireTheExpectedStatus() {
        FreightLoad notAccepted = load(LoadStatus.AVAILABLE, AssignmentStatus.NOTIFIED);
        FreightLoadService service = serviceWith(notAccepted);

        assertThatThrownBy(() -> service.startTrip(30L, DRIVER))
                .isInstanceOf(InvalidLoadOperationException.class)
                .hasMessageContaining("se esperaba status ASSIGNED");
        assertThatThrownBy(() -> service.deliverLoad(30L, DRIVER, pod()))
                .isInstanceOf(InvalidLoadOperationException.class)
                .hasMessageContaining("se esperaba status IN_TRANSIT");
        verify(outboxEventService, never()).enqueueLoadEvent(any(), any());
    }

    @Test
    void coordinatorCancelsLoadBeforeTripStarts() {
        FreightLoad load = load(LoadStatus.AVAILABLE, AssignmentStatus.NOTIFIED);
        FreightLoadService service = serviceWith(load);

        FreightLoadResponse cancelled = service.cancelLoad(30L, "coord-1", "COORDINADOR",
                CancelLoadRequest.builder().reason("El generador retiró la carga").build());

        assertThat(cancelled.getStatus()).isEqualTo(LoadStatus.CANCELLED);
        assertThat(cancelled.getCancellationReason()).isEqualTo("El generador retiró la carga");
        assertThat(cancelled.getCancelledBy()).isEqualTo("coord-1");
        verify(outboxEventService).enqueueLoadEvent(load, "load.cancelled");
    }

    @Test
    void cancellationRequiresCoordinatorOrAdminAndAPreTransitStatus() {
        CancelLoadRequest reason = CancelLoadRequest.builder().reason("Prueba").build();
        FreightLoad inTransit = load(LoadStatus.IN_TRANSIT, AssignmentStatus.ACCEPTED);
        FreightLoadService service = serviceWith(inTransit);

        assertThatThrownBy(() -> service.cancelLoad(30L, DRIVER, "CONDUCTOR", reason))
                .isInstanceOf(AssignmentAccessDeniedException.class)
                .satisfies(e -> assertThat(((AssignmentAccessDeniedException) e).isUnauthenticated()).isFalse());
        assertThatThrownBy(() -> service.cancelLoad(30L, null, null, reason))
                .isInstanceOf(AssignmentAccessDeniedException.class)
                .satisfies(e -> assertThat(((AssignmentAccessDeniedException) e).isUnauthenticated()).isTrue());
        assertThatThrownBy(() -> service.cancelLoad(30L, "admin-1", "ADMIN", reason))
                .isInstanceOf(InvalidLoadOperationException.class)
                .hasMessageContaining("IN_TRANSIT");
        assertThat(inTransit.getStatus()).isEqualTo(LoadStatus.IN_TRANSIT);
    }

    @Test
    void cancelledLoadIgnoresTheAssignmentCycle() {
        FreightLoad load = load(LoadStatus.CANCELLED, AssignmentStatus.NOTIFIED);
        load.setAssignmentStatusChangedAt(LocalDateTime.now().minusHours(2));
        FreightLoadService service = serviceWith(load);

        assertThat(service.expireIfUnanswered(30L, LocalDateTime.now().minusMinutes(5))).isFalse();
        assertThat(service.applyAssignmentNotified(30L, DRIVER)).isFalse();
        load.setAssignmentStatus(AssignmentStatus.REJECTED);
        assertThat(service.proposeReassignment(30L, "driver-new", "Nuevo", "NEW123")).isFalse();
        load.setAssignmentStatus(AssignmentStatus.NOTIFIED);
        assertThatThrownBy(() -> service.acceptAssignment(30L, DRIVER))
                .isInstanceOf(InvalidLoadOperationException.class)
                .hasMessageContaining("CANCELLED");

        assertThat(load.getStatus()).isEqualTo(LoadStatus.CANCELLED);
        verify(outboxEventService, never()).enqueueLoadEvent(any(), any());
    }

    @Test
    void driverSeesOnlyPendingAcceptedAndInTransitLoads() {
        FreightLoad pending = FreightLoad.builder().id(1L).status(LoadStatus.AVAILABLE)
                .assignmentStatus(AssignmentStatus.NOTIFIED).assignedDriverId(DRIVER).build();
        FreightLoad inTransit = FreightLoad.builder().id(2L).status(LoadStatus.IN_TRANSIT)
                .assignmentStatus(AssignmentStatus.ACCEPTED).assignedDriverId(DRIVER).build();
        FreightLoad delivered = FreightLoad.builder().id(3L).status(LoadStatus.DELIVERED)
                .assignmentStatus(AssignmentStatus.ACCEPTED).assignedDriverId(DRIVER).build();
        FreightLoad rejected = FreightLoad.builder().id(4L).status(LoadStatus.AVAILABLE)
                .assignmentStatus(AssignmentStatus.REJECTED).assignedDriverId(DRIVER).build();
        when(repository.findByAssignedDriverIdOrderByAssignedAtDesc(DRIVER))
                .thenReturn(List.of(pending, inTransit, delivered, rejected));
        FreightLoadService service = new FreightLoadService(repository, outboxEventService);

        assertThat(service.getLoadsForDriver(DRIVER)).extracting(FreightLoadResponse::getId).containsExactly(1L, 2L);
        assertThatThrownBy(() -> service.getLoadsForDriver(" "))
                .isInstanceOf(AssignmentAccessDeniedException.class);
    }
}
