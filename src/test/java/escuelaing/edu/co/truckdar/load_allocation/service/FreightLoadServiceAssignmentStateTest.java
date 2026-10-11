package escuelaing.edu.co.truckdar.load_allocation.service;

import escuelaing.edu.co.truckdar.load_allocation.dto.request.AssignDriverRequest;
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
        FreightLoadService service = new FreightLoadService(repository, outboxEventService);
        AssignDriverRequest request = AssignDriverRequest.builder()
                .driverId("driver-7")
                .driverName("Driver Seven")
                .vehiclePlate("ABC123")
                .build();

        service.assignDriver(7L, request);
        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.PROPOSED);

        service.notifyAssignment(7L);
        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.NOTIFIED);

        service.acceptAssignment(7L);
        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.ACCEPTED);
        assertThat(load.getStatus()).isEqualTo(LoadStatus.ASSIGNED);

        verify(outboxEventService).enqueueLoadEvent(load, "assignment.proposed");
        verify(outboxEventService).enqueueLoadEvent(load, "assignment.notified");
        verify(outboxEventService).enqueueLoadEvent(load, "assignment.accepted");
    }

    @Test
    void rejectsInvalidTransitionAndDoesNotPersistIt() {
        FreightLoad load = FreightLoad.builder()
                .id(8L)
                .status(LoadStatus.AVAILABLE)
                .assignmentStatus(AssignmentStatus.PROPOSED)
                .build();
        when(repository.findById(8L)).thenReturn(Optional.of(load));
        FreightLoadService service = new FreightLoadService(repository, outboxEventService);

        assertThatThrownBy(() -> service.acceptAssignment(8L))
                .isInstanceOf(InvalidLoadOperationException.class);
        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.PROPOSED);
    }
}
