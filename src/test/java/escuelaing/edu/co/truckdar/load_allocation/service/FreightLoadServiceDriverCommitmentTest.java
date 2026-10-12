package escuelaing.edu.co.truckdar.load_allocation.service;

import escuelaing.edu.co.truckdar.load_allocation.dto.request.AssignDriverRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.response.DriverCommitmentResponse;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FreightLoadServiceDriverCommitmentTest {

    private static final String DRIVER = "driver-ana";

    @Mock
    private FreightLoadRepository repository;

    @Mock
    private OutboxEventService outboxEventService;

    private FreightLoadService service() {
        when(repository.save(any(FreightLoad.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return new FreightLoadService(repository, outboxEventService,
                org.mockito.Mockito.mock(escuelaing.edu.co.truckdar.load_allocation.repository.ProofOfDeliveryMediaRepository.class));
    }

    private FreightLoad load(Long id, LoadStatus status, AssignmentStatus assignment, String driverId) {
        FreightLoad load = FreightLoad.builder()
                .id(id)
                .status(status)
                .assignmentStatus(assignment)
                .assignedDriverId(driverId)
                .build();
        when(repository.findById(id)).thenReturn(Optional.of(load));
        return load;
    }

    private void commitments(FreightLoad... loads) {
        when(repository.findDriverCommitments(anyCollection(), eq(LoadStatus.AVAILABLE), anyCollection()))
                .thenReturn(List.of(loads));
    }

    private AssignDriverRequest assignTo(String driverId) {
        return AssignDriverRequest.builder().driverId(driverId).driverName("Ana").vehiclePlate("ABC123").build();
    }

    @Test
    void listsDriversWithAcceptedInTransitOrPendingLoads() {
        commitments(
                load(1L, LoadStatus.ASSIGNED, AssignmentStatus.ACCEPTED, "d1"),
                load(2L, LoadStatus.IN_TRANSIT, AssignmentStatus.ACCEPTED, "d2"),
                load(3L, LoadStatus.AVAILABLE, AssignmentStatus.NOTIFIED, "d3"));

        List<DriverCommitmentResponse> result = service().getDriverCommitments();

        assertThat(result).extracting(DriverCommitmentResponse::getDriverId).containsExactly("d1", "d2", "d3");
        assertThat(result).extracting(DriverCommitmentResponse::getLoadStatus)
                .containsExactly(LoadStatus.ASSIGNED, LoadStatus.IN_TRANSIT, LoadStatus.AVAILABLE);
    }

    @Test
    void assigningADriverWhoIsInTransitWithAnotherLoadIsRejected() {
        load(40L, LoadStatus.AVAILABLE, null, null);
        commitments(load(41L, LoadStatus.IN_TRANSIT, AssignmentStatus.ACCEPTED, DRIVER));

        assertThatThrownBy(() -> service().assignDriver(40L, assignTo(DRIVER)))
                .isInstanceOf(InvalidLoadOperationException.class)
                .hasMessageContaining("ocupado con la carga #41")
                .hasMessageContaining("IN_TRANSIT");
        verify(outboxEventService, never()).enqueueLoadEvent(any(), any());
    }

    @Test
    void assigningADriverWithAPendingProposalElsewhereIsRejected() {
        load(40L, LoadStatus.AVAILABLE, null, null);
        commitments(load(42L, LoadStatus.AVAILABLE, AssignmentStatus.NOTIFIED, DRIVER));

        assertThatThrownBy(() -> service().assignDriver(40L, assignTo(DRIVER)))
                .isInstanceOf(InvalidLoadOperationException.class)
                .hasMessageContaining("propuesta NOTIFIED");
    }

    @Test
    void freeDriverIsProposed() {
        FreightLoad target = load(40L, LoadStatus.AVAILABLE, null, null);
        commitments(load(43L, LoadStatus.ASSIGNED, AssignmentStatus.ACCEPTED, "otro-conductor"));

        service().assignDriver(40L, assignTo(DRIVER));

        assertThat(target.getAssignmentStatus()).isEqualTo(AssignmentStatus.PROPOSED);
        assertThat(target.getAssignedDriverId()).isEqualTo(DRIVER);
    }

    @Test
    void acceptingASecondLoadWhileAnotherIsAssignedIsRejected() {
        FreightLoad proposal = load(50L, LoadStatus.AVAILABLE, AssignmentStatus.NOTIFIED, DRIVER);
        FreightLoad active = load(51L, LoadStatus.ASSIGNED, AssignmentStatus.ACCEPTED, DRIVER);
        when(repository.findByAssignedDriverIdAndStatusIn(eq(DRIVER), anyCollection())).thenReturn(List.of(active));

        assertThatThrownBy(() -> service().acceptAssignment(50L, DRIVER))
                .isInstanceOf(InvalidLoadOperationException.class)
                .hasMessageContaining("carga #51");
        assertThat(proposal.getAssignmentStatus()).isEqualTo(AssignmentStatus.NOTIFIED);
        assertThat(proposal.getStatus()).isEqualTo(LoadStatus.AVAILABLE);
    }

    @Test
    void acceptingWithoutOtherActiveLoadsSucceeds() {
        FreightLoad proposal = load(50L, LoadStatus.AVAILABLE, AssignmentStatus.NOTIFIED, DRIVER);
        when(repository.findByAssignedDriverIdAndStatusIn(eq(DRIVER), anyCollection())).thenReturn(List.of());

        service().acceptAssignment(50L, DRIVER);

        assertThat(proposal.getStatus()).isEqualTo(LoadStatus.ASSIGNED);
        assertThat(proposal.getAssignmentStatus()).isEqualTo(AssignmentStatus.ACCEPTED);
    }
}
