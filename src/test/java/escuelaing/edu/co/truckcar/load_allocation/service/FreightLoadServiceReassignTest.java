package escuelaing.edu.co.truckcar.load_allocation.service;

import escuelaing.edu.co.truckcar.load_allocation.dto.request.AssignDriverRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.response.FreightLoadResponse;
import escuelaing.edu.co.truckcar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckcar.load_allocation.exception.FreightLoadNotFoundException;
import escuelaing.edu.co.truckcar.load_allocation.exception.InvalidLoadOperationException;
import escuelaing.edu.co.truckcar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckcar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.TruckTypeRequired;
import escuelaing.edu.co.truckcar.load_allocation.repository.FreightLoadRepository;
import escuelaing.edu.co.truckcar.load_allocation.repository.ProofOfDeliveryMediaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PATCH /loads/{id}/reassign: la torre elige al conductor tras rechazos o le quita una carga aceptada antes de salir. */
class FreightLoadServiceReassignTest {

    private static final Long LOAD_ID = 40L;
    private static final AssignDriverRequest TO_LUIS = new AssignDriverRequest(" luis ", "Luis Gómez", "LUI-202");

    private FreightLoadRepository repository;
    private OutboxEventService outbox;
    private FreightLoadService service;
    private final List<FreightLoad> commitments = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(FreightLoadRepository.class);
        outbox = mock(OutboxEventService.class);
        when(repository.save(any(FreightLoad.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findDriverCommitments(any(), any(), any())).thenAnswer(invocation -> commitments);
        service = new FreightLoadService(repository, outbox, mock(ProofOfDeliveryMediaRepository.class));
    }

    private FreightLoad givenLoad(LoadStatus status, AssignmentStatus assignment, String driver) {
        FreightLoad load = FreightLoad.builder()
                .id(LOAD_ID).status(status).assignmentStatus(assignment)
                .assignedDriverId(driver).assignedDriverName(driver == null ? null : "Ana Pérez")
                .assignedVehiclePlate(driver == null ? null : "ANA-101")
                .assignedAt(status == LoadStatus.ASSIGNED ? LocalDateTime.now().minusMinutes(20) : null)
                .assignmentAttempts(3)
                .requiredTruckType(TruckTypeRequired.TURBO)
                .discardedDriverIds(new HashSet<>())
                .build();
        when(repository.findById(LOAD_ID)).thenReturn(Optional.of(load));
        return load;
    }

    private FreightLoadResponse reassign() {
        return service.reassignLoad(LOAD_ID, "coord-1", "COORDINADOR", TO_LUIS);
    }

    @ParameterizedTest(name = "después de {0} la torre propone al conductor que elija")
    @EnumSource(value = AssignmentStatus.class, names = {"REJECTED", "TIMEOUT"})
    void afterARejectionOrTimeoutTheTowerChoosesTheNextDriver(AssignmentStatus previous) {
        FreightLoad load = givenLoad(LoadStatus.AVAILABLE, previous, "ana");
        load.getDiscardedDriverIds().add("ana");

        FreightLoadResponse result = reassign();

        assertThat(result.getAssignmentStatus()).isEqualTo(AssignmentStatus.PROPOSED);
        assertThat(result.getAssignedDriverId()).isEqualTo("luis");
        assertThat(result.getAssignedVehiclePlate()).isEqualTo("LUI-202");
        assertThat(load.getAssignmentAttempts()).as("cuenta como un intento más").isEqualTo(4);
        verify(outbox).enqueueLoadEvent(load, "assignment.proposed");
        verify(outbox, never()).enqueueLoadEvent(any(), eq("assignment.revoked"));
    }

    @Test
    void aLoadThatNeverHadAProposalCanAlsoBeReassigned() {
        givenLoad(LoadStatus.AVAILABLE, null, null);

        assertThat(reassign().getAssignmentStatus()).isEqualTo(AssignmentStatus.PROPOSED);
    }

    @Test
    void aLoadLeftWithoutDriversStartsANewCycleOfAttempts() {
        FreightLoad load = givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.UNASSIGNED_NO_DRIVERS, null);

        reassign();

        assertThat(load.getAssignmentAttempts()).isEqualTo(1);
        assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.PROPOSED);
    }

    /** El evento de revocación sale antes de la propuesta y con el conductor anterior: fleets-drivers lo libera. */
    @Test
    void anAcceptedLoadIsTakenFromItsDriverAndProposedToTheNewOne() {
        FreightLoad load = givenLoad(LoadStatus.ASSIGNED, AssignmentStatus.ACCEPTED, "ana");
        AtomicReference<String> atRevocation = new AtomicReference<>();
        doAnswer(invocation -> {
            FreightLoad l = invocation.getArgument(0);
            atRevocation.set(l.getStatus() + "/" + l.getAssignedDriverId() + "/" + l.getAssignedAt());
            return null;
        }).when(outbox).enqueueLoadEvent(any(FreightLoad.class), eq("assignment.revoked"));

        FreightLoadResponse result = reassign();

        assertThat(atRevocation.get()).isEqualTo("AVAILABLE/ana/null");
        InOrder order = inOrder(outbox);
        order.verify(outbox).enqueueLoadEvent(load, "assignment.revoked");
        order.verify(outbox).enqueueLoadEvent(load, "assignment.proposed");
        assertThat(result.getStatus()).isEqualTo(LoadStatus.AVAILABLE);
        assertThat(result.getAssignmentStatus()).isEqualTo(AssignmentStatus.PROPOSED);
        assertThat(result.getAssignedDriverId()).isEqualTo("luis");
        assertThat(load.getDiscardedDriverIds()).as("no se le vuelve a proponer").contains("ana");
    }

    @ParameterizedTest(name = "con una propuesta {0} vigente no se reasigna")
    @EnumSource(value = AssignmentStatus.class, names = {"PROPOSED", "NOTIFIED"})
    void aPendingProposalIsNotOverridden(AssignmentStatus pending) {
        FreightLoad load = givenLoad(LoadStatus.AVAILABLE, pending, "ana");

        assertThatThrownBy(this::reassign).isInstanceOf(InvalidLoadOperationException.class)
                .hasMessageContaining("propuesta vigente (" + pending + ") para Ana Pérez")
                .hasMessageContaining("espere");
        assertThat(load.getAssignedDriverId()).isEqualTo("ana");
        verify(outbox, never()).enqueueLoadEvent(any(), anyString());
    }

    @ParameterizedTest(name = "una carga {0} no se reasigna")
    @EnumSource(value = LoadStatus.class, names = {"IN_TRANSIT", "DELIVERED", "CANCELLED"})
    void onceTheTripStartedItCannotBeReassigned(LoadStatus status) {
        givenLoad(status, AssignmentStatus.ACCEPTED, "ana");

        assertThatThrownBy(this::reassign).isInstanceOf(InvalidLoadOperationException.class)
                .hasMessageContaining("status " + status).hasMessageContaining("antes de iniciar el viaje");
        verify(repository, never()).save(any());
    }

    @Test
    void anAssignedLoadThatIsNotAcceptedIsNotReassigned() {
        givenLoad(LoadStatus.ASSIGNED, AssignmentStatus.NOTIFIED, "ana");

        assertThatThrownBy(this::reassign).isInstanceOf(InvalidLoadOperationException.class);
    }

    @Test
    void theDriverWhoAlreadyHasItCannotReceiveItAgain() {
        givenLoad(LoadStatus.ASSIGNED, AssignmentStatus.ACCEPTED, "luis");

        assertThatThrownBy(this::reassign).isInstanceOf(InvalidLoadOperationException.class)
                .hasMessageContaining("ya tiene aceptada");
    }

    @Test
    void aDriverWhoRejectedTheLoadIsNotProposedAgain() {
        FreightLoad load = givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.REJECTED, "ana");
        load.getDiscardedDriverIds().add("luis");

        assertThatThrownBy(this::reassign).isInstanceOf(InvalidLoadOperationException.class)
                .hasMessageContaining("ya rechazó");
    }

    @Test
    void aBusyDriverCannotReceiveIt() {
        givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.TIMEOUT, "ana");
        commitments.add(FreightLoad.builder().id(77L).status(LoadStatus.IN_TRANSIT).assignedDriverId("luis").build());

        assertThatThrownBy(this::reassign).isInstanceOf(InvalidLoadOperationException.class)
                .hasMessageContaining("ocupado con la carga #77 (IN_TRANSIT)");
    }

    @ParameterizedTest(name = "el rol {0} no reasigna")
    @ValueSource(strings = {"CONDUCTOR", "GENERADOR", "SYSTEM"})
    void onlyTheControlTowerReassigns(String role) {
        givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.REJECTED, "ana");

        assertThatThrownBy(() -> service.reassignLoad(LOAD_ID, "u-1", role, TO_LUIS))
                .isInstanceOf(AssignmentAccessDeniedException.class)
                .satisfies(e -> assertThat(((AssignmentAccessDeniedException) e).isUnauthenticated()).isFalse())
                .hasMessageContaining(role);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {" "})
    void withoutRoleItIsUnauthenticated(String role) {
        assertThatThrownBy(() -> service.reassignLoad(LOAD_ID, null, role, TO_LUIS))
                .isInstanceOf(AssignmentAccessDeniedException.class)
                .satisfies(e -> assertThat(((AssignmentAccessDeniedException) e).isUnauthenticated()).isTrue());
    }

    @Test
    void anUnknownLoadIsNotFound() {
        when(repository.findById(LOAD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(this::reassign).isInstanceOf(FreightLoadNotFoundException.class);
    }

    /** Lote 4: el evento dice que la propuesta la hizo la torre, para que el aviso al conductor no culpe al motor. */
    @Test
    void theProposalSaysItCameFromTheTower() {
        FreightLoad load = givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.REJECTED, "ana");
        AtomicReference<String> proposedBy = new AtomicReference<>();
        doAnswer(invocation -> {
            proposedBy.set(invocation.<FreightLoad>getArgument(0).getProposedBy());
            return null;
        }).when(outbox).enqueueLoadEvent(any(FreightLoad.class), eq("assignment.proposed"));

        reassign();

        assertThat(proposedBy.get()).isEqualTo("TORRE");
    }

    @Test
    void assignDriverMarksTheProposalByWhoAskedForIt() {
        AtomicReference<String> proposedBy = new AtomicReference<>();
        doAnswer(invocation -> {
            proposedBy.set(invocation.<FreightLoad>getArgument(0).getProposedBy());
            return null;
        }).when(outbox).enqueueLoadEvent(any(FreightLoad.class), eq("assignment.proposed"));

        givenLoad(LoadStatus.AVAILABLE, null, null);
        service.assignDriver(LOAD_ID, TO_LUIS, "COORDINADOR");
        assertThat(proposedBy.get()).isEqualTo("TORRE");

        givenLoad(LoadStatus.AVAILABLE, null, null);
        service.assignDriver(LOAD_ID, TO_LUIS, "SYSTEM");
        assertThat(proposedBy.get()).isEqualTo("MOTOR");

        givenLoad(LoadStatus.AVAILABLE, null, null);
        service.assignDriver(LOAD_ID, TO_LUIS);
        assertThat(proposedBy.get()).as("sin rol: llamada interna del motor").isEqualTo("MOTOR");
    }
}
