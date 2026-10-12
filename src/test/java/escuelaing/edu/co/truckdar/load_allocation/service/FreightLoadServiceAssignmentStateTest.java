package escuelaing.edu.co.truckdar.load_allocation.service;

import escuelaing.edu.co.truckdar.load_allocation.dto.request.AssignDriverRequest;
import escuelaing.edu.co.truckdar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckdar.load_allocation.exception.InvalidLoadOperationException;
import escuelaing.edu.co.truckdar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckdar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckdar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckdar.load_allocation.repository.FreightLoadRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class FreightLoadServiceAssignmentStateTest {

    @Mock
    private FreightLoadRepository repository;

    @Mock
    private OutboxEventService outboxEventService;

    @Test
    void followsProposedNotifiedAcceptedAndPublishesEachTransition() {
        FreightLoad load = FreightLoad.builder()
                .id(7L)
                .status(LoadStatus.AVAILABLE)
                .build();
        when(repository.findById(7L)).thenReturn(Optional.of(load));
        when(repository.save(any(FreightLoad.class))).thenAnswer(invocation -> invocation.getArgument(0));
        FreightLoadService service = new FreightLoadService(repository, outboxEventService,
                org.mockito.Mockito.mock(escuelaing.edu.co.truckdar.load_allocation.repository.ProofOfDeliveryMediaRepository.class));
        AssignDriverRequest request = AssignDriverRequest.builder()
                .driverId("driver-7")
                .driverName("Driver Seven")
                .vehiclePlate("ABC123")
                .build();

        service.assignDriver(7L, request);
        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.PROPOSED);

        service.notifyAssignment(7L);
        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.NOTIFIED);

        service.acceptAssignment(7L, "driver-7");
        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.ACCEPTED);
        assertThat(load.getStatus()).isEqualTo(LoadStatus.ASSIGNED);

        verify(outboxEventService).enqueueLoadEvent(load, "assignment.proposed");
        verify(outboxEventService).enqueueLoadEvent(load, "assignment.notified");
        verify(outboxEventService).enqueueLoadEvent(load, "assignment.accepted");
    }

    @Test
    void appliesAssignmentNotifiedOnceAndIgnoresRedelivery() {
        FreightLoad load = FreightLoad.builder()
                .id(9L)
                .status(LoadStatus.AVAILABLE)
                .assignmentStatus(AssignmentStatus.PROPOSED)
                .assignedDriverId("driver-42")
                .build();
        when(repository.findById(9L)).thenReturn(Optional.of(load));
        when(repository.save(any(FreightLoad.class))).thenAnswer(invocation -> invocation.getArgument(0));
        FreightLoadService service = new FreightLoadService(repository, outboxEventService,
                org.mockito.Mockito.mock(escuelaing.edu.co.truckdar.load_allocation.repository.ProofOfDeliveryMediaRepository.class));

        assertThat(service.applyAssignmentNotified(9L, "driver-42")).isTrue();
        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.NOTIFIED);

        assertThat(service.applyAssignmentNotified(9L, "driver-42")).isFalse();
        verify(outboxEventService, times(1)).enqueueLoadEvent(load, "assignment.notified");
    }

    @Test
    void ignoresAssignmentNotifiedForStaleCandidate() {
        FreightLoad load = FreightLoad.builder()
                .id(10L)
                .status(LoadStatus.AVAILABLE)
                .assignmentStatus(AssignmentStatus.PROPOSED)
                .assignedDriverId("driver-2")
                .build();
        when(repository.findById(10L)).thenReturn(Optional.of(load));
        FreightLoadService service = new FreightLoadService(repository, outboxEventService,
                org.mockito.Mockito.mock(escuelaing.edu.co.truckdar.load_allocation.repository.ProofOfDeliveryMediaRepository.class));

        assertThat(service.applyAssignmentNotified(10L, "driver-1")).isFalse();
        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.PROPOSED);
        verify(outboxEventService, never()).enqueueLoadEvent(any(), any());
    }

    @Test
    void rejectsInvalidTransitionAndDoesNotPersistIt() {
        FreightLoad load = FreightLoad.builder()
                .id(8L)
                .status(LoadStatus.AVAILABLE)
                .assignmentStatus(AssignmentStatus.PROPOSED)
                .assignedDriverId("driver-8")
                .build();
        when(repository.findById(8L)).thenReturn(Optional.of(load));
        FreightLoadService service = new FreightLoadService(repository, outboxEventService,
                org.mockito.Mockito.mock(escuelaing.edu.co.truckdar.load_allocation.repository.ProofOfDeliveryMediaRepository.class));

        assertThatThrownBy(() -> service.acceptAssignment(8L, "driver-8"))
                .isInstanceOf(InvalidLoadOperationException.class);
        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.PROPOSED);
    }

    @Test
    void onlyTheAssignedDriverCanAcceptOrReject() {
        FreightLoad load = FreightLoad.builder()
                .id(11L)
                .status(LoadStatus.AVAILABLE)
                .assignmentStatus(AssignmentStatus.NOTIFIED)
                .assignedDriverId("driver-11")
                .build();
        when(repository.findById(11L)).thenReturn(Optional.of(load));
        FreightLoadService service = new FreightLoadService(repository, outboxEventService,
                org.mockito.Mockito.mock(escuelaing.edu.co.truckdar.load_allocation.repository.ProofOfDeliveryMediaRepository.class));

        assertThatThrownBy(() -> service.acceptAssignment(11L, "otro-usuario"))
                .isInstanceOf(AssignmentAccessDeniedException.class)
                .satisfies(e -> assertThat(((AssignmentAccessDeniedException) e).isUnauthenticated()).isFalse());
        assertThatThrownBy(() -> service.rejectAssignment(11L, null))
                .isInstanceOf(AssignmentAccessDeniedException.class)
                .satisfies(e -> assertThat(((AssignmentAccessDeniedException) e).isUnauthenticated()).isTrue());

        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.NOTIFIED);
        verify(outboxEventService, never()).enqueueLoadEvent(any(), any());
    }

    @Test
    void unassignedLoadCanBeReopenedForANewEligibleDriver() {
        FreightLoad load = FreightLoad.builder()
                .id(12L)
                .status(LoadStatus.AVAILABLE)
                .assignmentStatus(AssignmentStatus.UNASSIGNED_NO_DRIVERS)
                .assignmentAttempts(5)
                .assignedDriverId("driver-old")
                .build();
        load.getDiscardedDriverIds().add("driver-old");
        when(repository.findById(12L)).thenReturn(Optional.of(load));
        when(repository.save(any(FreightLoad.class))).thenAnswer(invocation -> invocation.getArgument(0));
        FreightLoadService service = new FreightLoadService(repository, outboxEventService,
                org.mockito.Mockito.mock(escuelaing.edu.co.truckdar.load_allocation.repository.ProofOfDeliveryMediaRepository.class));

        assertThatThrownBy(() -> service.assignDriver(12L, AssignDriverRequest.builder()
                .driverId("driver-old").driverName("Old").vehiclePlate("OLD123").build()))
                .isInstanceOf(InvalidLoadOperationException.class)
                .hasMessageContaining("ya rechazó o dejó vencer");

        service.assignDriver(12L, AssignDriverRequest.builder()
                .driverId("driver-new").driverName("New").vehiclePlate("NEW123").build());

        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.PROPOSED);
        assertThat(load.getAssignedDriverId()).isEqualTo("driver-new");
        assertThat(load.getAssignmentAttempts()).isEqualTo(1);
        assertThat(load.getDiscardedDriverIds()).containsExactly("driver-old");
        verify(outboxEventService).enqueueLoadEvent(load, "assignment.proposed");
    }

    @Test
    void assignRejectsLoadsWithAnActiveProposalAndReportsTheRealState() {
        FreightLoad load = FreightLoad.builder()
                .id(13L)
                .status(LoadStatus.AVAILABLE)
                .assignmentStatus(AssignmentStatus.NOTIFIED)
                .build();
        when(repository.findById(13L)).thenReturn(Optional.of(load));
        FreightLoadService service = new FreightLoadService(repository, outboxEventService,
                org.mockito.Mockito.mock(escuelaing.edu.co.truckdar.load_allocation.repository.ProofOfDeliveryMediaRepository.class));

        assertThatThrownBy(() -> service.assignDriver(13L, AssignDriverRequest.builder()
                .driverId("driver-x").driverName("X").vehiclePlate("XXX111").build()))
                .isInstanceOf(InvalidLoadOperationException.class)
                .hasMessageContaining("assignmentStatus=NOTIFIED");
    }
}
