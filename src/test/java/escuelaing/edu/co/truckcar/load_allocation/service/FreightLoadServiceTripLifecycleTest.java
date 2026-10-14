package escuelaing.edu.co.truckcar.load_allocation.service;

import escuelaing.edu.co.truckcar.load_allocation.dto.request.CancelLoadRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.DeliveryConfirmationRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.response.FreightLoadResponse;
import escuelaing.edu.co.truckcar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckcar.load_allocation.exception.InvalidLoadOperationException;
import escuelaing.edu.co.truckcar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckcar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.ProofOfDeliveryMedia;
import escuelaing.edu.co.truckcar.load_allocation.repository.FreightLoadRepository;
import escuelaing.edu.co.truckcar.load_allocation.repository.ProofOfDeliveryMediaRepository;
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

    @Mock
    private ProofOfDeliveryMediaRepository podMediaRepository;

    private FreightLoadService serviceWith(FreightLoad load) {
        when(repository.findById(load.getId())).thenReturn(Optional.of(load));
        when(repository.save(any(FreightLoad.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return new FreightLoadService(repository, outboxEventService, podMediaRepository);
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
        FreightLoadService service = new FreightLoadService(repository, outboxEventService, podMediaRepository);

        assertThat(service.getLoadsForDriver(DRIVER)).extracting(FreightLoadResponse::getId).containsExactly(1L, 2L);
        assertThatThrownBy(() -> service.getLoadsForDriver(" "))
                .isInstanceOf(AssignmentAccessDeniedException.class);
    }

    private static final String SIGNATURE = "data:image/png;base64,iVBORw0KGgo=";
    private static final String PHOTO = "data:image/jpeg;base64,/9j/4AAQSkZJRg==";

    @Test
    void deliveryStoresSignatureAndPhotoOutsideTheLoad() {
        FreightLoad load = load(LoadStatus.IN_TRANSIT, AssignmentStatus.ACCEPTED);
        FreightLoadService service = serviceWith(load);
        DeliveryConfirmationRequest pod = pod();
        pod.setSignatureDataUrl(SIGNATURE);
        pod.setPhotoUrl(PHOTO);

        FreightLoadResponse delivered = service.deliverLoad(30L, DRIVER, pod);

        assertThat(delivered.isPodHasSignature()).isTrue();
        assertThat(delivered.isPodHasPhoto()).isTrue();
        org.mockito.ArgumentCaptor<ProofOfDeliveryMedia> media = org.mockito.ArgumentCaptor.forClass(ProofOfDeliveryMedia.class);
        verify(podMediaRepository).save(media.capture());
        assertThat(media.getValue().getLoadId()).isEqualTo(30L);
        assertThat(media.getValue().getSignatureDataUrl()).isEqualTo(SIGNATURE);
        assertThat(media.getValue().getPhotoUrl()).isEqualTo(PHOTO);
    }

    @Test
    void deliveryWithoutMediaDoesNotWriteTheMediaTable() {
        FreightLoad load = load(LoadStatus.IN_TRANSIT, AssignmentStatus.ACCEPTED);

        FreightLoadResponse delivered = serviceWith(load).deliverLoad(30L, DRIVER, pod());

        assertThat(delivered.isPodHasSignature()).isFalse();
        verify(podMediaRepository, never()).save(any());
    }

    @Test
    void proofOfDeliveryIsVisibleToItsDriverAndTheControlTowerOnly() {
        FreightLoad load = load(LoadStatus.DELIVERED, AssignmentStatus.ACCEPTED);
        load.setPodReceiverName("Jefe de Bodega");
        load.setPodHasSignature(true);
        when(podMediaRepository.findById(30L)).thenReturn(Optional.of(ProofOfDeliveryMedia.builder()
                .loadId(30L).signatureDataUrl(SIGNATURE).photoUrl(PHOTO).build()));
        FreightLoadService service = serviceWith(load);

        assertThat(service.getProofOfDelivery(30L, DRIVER, "CONDUCTOR").getSignatureDataUrl()).isEqualTo(SIGNATURE);
        assertThat(service.getProofOfDelivery(30L, "coord-1", "COORDINADOR").getPhotoUrl()).isEqualTo(PHOTO);
        assertThat(service.getProofOfDelivery(30L, "coord-1", "COORDINADOR").getReceiverName()).isEqualTo("Jefe de Bodega");
        assertThatThrownBy(() -> service.getProofOfDelivery(30L, "otro-conductor", "CONDUCTOR"))
                .isInstanceOf(AssignmentAccessDeniedException.class);
        assertThatThrownBy(() -> service.getProofOfDelivery(30L, null, null))
                .isInstanceOf(AssignmentAccessDeniedException.class);
    }

    @Test
    void proofOfDeliveryRequiresADeliveredLoad() {
        FreightLoadService service = serviceWith(load(LoadStatus.IN_TRANSIT, AssignmentStatus.ACCEPTED));

        assertThatThrownBy(() -> service.getProofOfDelivery(30L, DRIVER, "CONDUCTOR"))
                .isInstanceOf(InvalidLoadOperationException.class)
                .hasMessageContaining("IN_TRANSIT");
    }

    @Test
    void controlTowerListsLoadsByStatusButDriversCannot() {
        FreightLoad delivered = load(LoadStatus.DELIVERED, AssignmentStatus.ACCEPTED);
        when(repository.findTop200ByStatusInOrderByPublishedAtDesc(List.of(LoadStatus.DELIVERED)))
                .thenReturn(List.of(delivered));
        FreightLoadService service = serviceWith(delivered);

        assertThat(service.listLoads(List.of(LoadStatus.DELIVERED), "coord-1", "COORDINADOR"))
                .extracting(FreightLoadResponse::getStatus).containsExactly(LoadStatus.DELIVERED);
        assertThatThrownBy(() -> service.listLoads(null, DRIVER, "CONDUCTOR"))
                .isInstanceOf(AssignmentAccessDeniedException.class);
    }
}
