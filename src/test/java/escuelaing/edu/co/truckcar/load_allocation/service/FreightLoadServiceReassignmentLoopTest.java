package escuelaing.edu.co.truckcar.load_allocation.service;

import escuelaing.edu.co.truckcar.load_allocation.dto.request.AssignDriverRequest;
import escuelaing.edu.co.truckcar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckcar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckcar.load_allocation.repository.FreightLoadRepository;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FreightLoadServiceReassignmentLoopTest {

    @Mock
    private FreightLoadRepository repository;

    @Mock
    private OutboxEventService outboxEventService;

    private FreightLoadService service;
    private FreightLoad load;

    @BeforeEach
    void setUp() {
        load = FreightLoad.builder()
                .id(21L)
                .status(LoadStatus.AVAILABLE)
                .build();
        when(repository.findById(21L)).thenReturn(Optional.of(load));
        when(repository.save(any(FreightLoad.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new FreightLoadService(repository, outboxEventService,
                org.mockito.Mockito.mock(escuelaing.edu.co.truckcar.load_allocation.repository.ProofOfDeliveryMediaRepository.class));
    }

    @Test
    void rejectedLoadIsProposedAgainToNewCandidateAndDiscardsPreviousDriver() {
        proposeAndNotify("driver-1");
        service.rejectAssignment(21L, load.getAssignedDriverId());

        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.REJECTED);
        assertThat(load.getDiscardedDriverIds()).containsExactly("driver-1");

        boolean proposed = service.proposeReassignment(21L, "driver-2", "Driver Two", "XYZ987");

        assertThat(proposed).isTrue();
        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.PROPOSED);
        assertThat(load.getAssignedDriverId()).isEqualTo("driver-2");
        assertThat(load.getAssignmentAttempts()).isEqualTo(2);
        verify(outboxEventService, times(2)).enqueueLoadEvent(load, "assignment.proposed");
    }

    @Test
    void timedOutLoadIsProposedAgainAndDuplicateRecommendationIsIgnored() {
        proposeAndNotify("driver-1");
        service.timeoutAssignment(21L);

        assertThat(service.proposeReassignment(21L, "driver-2", "Driver Two", "XYZ987")).isTrue();
        assertThat(service.proposeReassignment(21L, "driver-2", "Driver Two", "XYZ987")).isFalse();

        assertThat(load.getAssignmentAttempts()).isEqualTo(2);
        assertThat(load.getDiscardedDriverIds()).containsExactly("driver-1");
    }

    @Test
    void recommendationOfDiscardedDriverIsIgnoredToAvoidPingPong() {
        proposeAndNotify("driver-1");
        service.rejectAssignment(21L, load.getAssignedDriverId());
        service.proposeReassignment(21L, "driver-2", "Driver Two", "XYZ987");
        service.applyAssignmentNotified(21L, "driver-2");
        service.rejectAssignment(21L, load.getAssignedDriverId());

        boolean proposed = service.proposeReassignment(21L, "driver-1", "Driver One", "ABC123");

        assertThat(proposed).isFalse();
        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.REJECTED);
        assertThat(load.getDiscardedDriverIds()).containsExactly("driver-1", "driver-2");
    }

    @Test
    void exceedingMaxAttemptsMarksLoadUnassignedNoDrivers() {
        proposeAndNotify("driver-1");
        for (int attempt = 2; attempt <= 5; attempt++) {
            service.rejectAssignment(21L, load.getAssignedDriverId());
            assertThat(service.proposeReassignment(21L, "driver-" + attempt, "Driver", "PLATE")).isTrue();
            service.applyAssignmentNotified(21L, "driver-" + attempt);
        }
        service.rejectAssignment(21L, load.getAssignedDriverId());

        boolean proposed = service.proposeReassignment(21L, "driver-6", "Driver Six", "PLATE6");

        assertThat(proposed).isFalse();
        assertThat(load.getAssignmentAttempts()).isEqualTo(5);
        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.UNASSIGNED_NO_DRIVERS);
        verify(outboxEventService).enqueueLoadEvent(load, "assignment.unassigned");
    }

    @Test
    void noCandidatesMarksRejectedLoadUnassigned() {
        proposeAndNotify("driver-1");
        service.rejectAssignment(21L, load.getAssignedDriverId());

        assertThat(service.markNoDriversAvailable(21L)).isTrue();
        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.UNASSIGNED_NO_DRIVERS);

        assertThat(service.markNoDriversAvailable(21L)).isFalse();
        verify(outboxEventService, times(1)).enqueueLoadEvent(load, "assignment.unassigned");
    }

    @Test
    void expiresOnlyNotificationsOlderThanCutoff() {
        proposeAndNotify("driver-1");
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);

        assertThat(service.expireIfUnanswered(21L, cutoff)).isFalse();
        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.NOTIFIED);

        load.setAssignmentStatusChangedAt(LocalDateTime.now().minusMinutes(6));
        assertThat(service.expireIfUnanswered(21L, cutoff)).isTrue();

        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.TIMEOUT);
        assertThat(load.getDiscardedDriverIds()).containsExactly("driver-1");
        verify(outboxEventService).enqueueLoadEvent(load, "assignment.timeout");
    }

    @Test
    void schedulerTimesOutExpiredNotifiedLoads() {
        proposeAndNotify("driver-1");
        load.setAssignmentStatusChangedAt(LocalDateTime.now().minusMinutes(10));
        when(repository.findByAssignmentStatusAndAssignmentStatusChangedAtBefore(
                any(AssignmentStatus.class), any(LocalDateTime.class))).thenReturn(List.of(load));

        new AssignmentTimeoutScheduler(repository, service).expireUnansweredAssignments();

        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.TIMEOUT);
        verify(outboxEventService).enqueueLoadEvent(load, "assignment.timeout");
        verify(outboxEventService, never()).enqueueLoadEvent(load, "assignment.unassigned");
    }

    private void proposeAndNotify(String driverId) {
        service.assignDriver(21L, AssignDriverRequest.builder()
                .driverId(driverId)
                .driverName("Driver")
                .vehiclePlate("ABC123")
                .build());
        service.applyAssignmentNotified(21L, driverId);
    }
}
