package escuelaing.edu.co.truckcar.load_allocation.service;

import escuelaing.edu.co.truckcar.load_allocation.dto.request.AssignDriverRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.CancelLoadRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.CreateFreightLoadRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.DeliveryConfirmationRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.PodObjectReference;
import escuelaing.edu.co.truckcar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckcar.load_allocation.exception.InvalidLoadOperationException;
import escuelaing.edu.co.truckcar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckcar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.TruckTypeRequired;
import escuelaing.edu.co.truckcar.load_allocation.repository.FreightLoadRepository;
import escuelaing.edu.co.truckcar.load_allocation.repository.ProofOfDeliveryMediaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Matriz completa de la máquina de estados de una carga (KAN-44): LoadStatus × (AssignmentStatus + null) × operación.
 * Solo las combinaciones de {@link #VALID} tienen éxito; el resto no cambia la entidad ni encola eventos en el outbox.
 * <p>
 * Tabla de transiciones válidas, leída de FreightLoadService. Diferencias con la tabla de la subtarea KAN-44, que son
 * comportamiento intencional del código y quedan fijadas aquí:
 * <ul>
 *   <li><b>accept/reject solo desde NOTIFIED</b>, no desde PROPOSED: el conductor no puede responder una propuesta de
 *       la que Notifications aún no le avisó (la app solo muestra propuestas notificadas).</li>
 *   <li><b>timeout y expireIfUnanswered también desde PROPOSED</b>: si Notifications nunca confirma el aviso, la
 *       propuesta vencía nunca y la carga quedaba bloqueada.</li>
 *   <li><b>markNoDriversAvailable solo desde null, REJECTED o TIMEOUT</b> (no "cualquiera salvo ACCEPTED"): una
 *       propuesta vigente (PROPOSED/NOTIFIED) no se pisa con un aviso tardío del motor, y ya UNASSIGNED es idempotente.</li>
 *   <li><b>assignDriver desde null o UNASSIGNED_NO_DRIVERS</b>; desde REJECTED/TIMEOUT la reasignación entra por
 *       proposeReassignment (agent.score.ready), no por PATCH /assign.</li>
 *   <li><b>startTrip, deliver y cancel miran solo LoadStatus</b>: ASSIGNED implica ACCEPTED en el flujo real, así que
 *       el assignmentStatus no se vuelve a comprobar.</li>
 * </ul>
 * Las operaciones del motor (proposeReassignment, applyAssignmentNotified, expireIfUnanswered, markNoDriversAvailable)
 * llegan por Kafka o por el scheduler: ante un estado inválido devuelven false en lugar de lanzar (no hay a quién
 * responder un 409 y el mensaje no debe reintentarse).
 */
@DisplayName("Máquina de estados de la carga: matriz completa (KAN-44)")
class FreightLoadStateMachineMatrixTest {

    private static final long LOAD_ID = 1L;
    private static final String DRIVER = "driver-actual";
    private static final String NEW_DRIVER = "driver-nuevo";
    private static final String OTHER_USER = "otro-usuario";
    private static final String GENERATOR = "generador-duenio";

    private FreightLoadRepository repository;
    private OutboxEventService outbox;
    private FreightLoadService service;

    @BeforeEach
    void setUp() {
        repository = mock(FreightLoadRepository.class);
        outbox = mock(OutboxEventService.class);
        when(repository.save(any(FreightLoad.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new FreightLoadService(repository, outbox, mock(ProofOfDeliveryMediaRepository.class));
    }

    /** Operación del servicio y el resultado que produce cuando es válida. */
    enum Operation {
        ASSIGN("assignment.proposed", LoadStatus.AVAILABLE, AssignmentStatus.PROPOSED, false,
                (service, user) -> service.assignDriver(LOAD_ID, new AssignDriverRequest(NEW_DRIVER, "Nuevo", "NEW-001"))),
        PROPOSE_REASSIGNMENT("assignment.proposed", LoadStatus.AVAILABLE, AssignmentStatus.PROPOSED, true,
                (service, user) -> service.proposeReassignment(LOAD_ID, NEW_DRIVER, "Nuevo", "NEW-001")),
        NOTIFY("assignment.notified", LoadStatus.AVAILABLE, AssignmentStatus.NOTIFIED, false,
                (service, user) -> service.notifyAssignment(LOAD_ID)),
        APPLY_NOTIFIED("assignment.notified", LoadStatus.AVAILABLE, AssignmentStatus.NOTIFIED, true,
                (service, user) -> service.applyAssignmentNotified(LOAD_ID, DRIVER)),
        ACCEPT("assignment.accepted", LoadStatus.ASSIGNED, AssignmentStatus.ACCEPTED, false,
                (service, user) -> service.acceptAssignment(LOAD_ID, user)),
        REJECT("assignment.rejected", LoadStatus.AVAILABLE, AssignmentStatus.REJECTED, false,
                (service, user) -> service.rejectAssignment(LOAD_ID, user)),
        TIMEOUT("assignment.timeout", LoadStatus.AVAILABLE, AssignmentStatus.TIMEOUT, false,
                (service, user) -> service.timeoutAssignment(LOAD_ID)),
        EXPIRE_IF_UNANSWERED("assignment.timeout", LoadStatus.AVAILABLE, AssignmentStatus.TIMEOUT, true,
                (service, user) -> service.expireIfUnanswered(LOAD_ID, LocalDateTime.now().plusMinutes(1))),
        MARK_NO_DRIVERS("assignment.unassigned", LoadStatus.AVAILABLE, AssignmentStatus.UNASSIGNED_NO_DRIVERS, true,
                (service, user) -> service.markNoDriversAvailable(LOAD_ID)),
        START_TRIP("trip.started", LoadStatus.IN_TRANSIT, null, false,
                (service, user) -> service.startTrip(LOAD_ID, user)),
        DELIVER("trip.delivered", LoadStatus.DELIVERED, null, false,
                (service, user) -> service.deliverLoad(LOAD_ID, user, pod())),
        CANCEL("load.cancelled", LoadStatus.CANCELLED, null, false,
                (service, user) -> service.cancelLoad(LOAD_ID, "coordinador-1", "COORDINADOR",
                        new CancelLoadRequest("Prueba de la matriz")));

        final String event;
        final LoadStatus resultingStatus;
        /** null: la operación no cambia assignmentStatus. */
        final AssignmentStatus resultingAssignment;
        /** Operación del motor (Kafka o scheduler): ante un estado inválido devuelve false en lugar de lanzar. */
        final boolean returnsFalseWhenInvalid;
        final BiFunction<FreightLoadService, String, Object> call;

        Operation(String event, LoadStatus resultingStatus, AssignmentStatus resultingAssignment,
                  boolean returnsFalseWhenInvalid, BiFunction<FreightLoadService, String, Object> call) {
            this.event = event;
            this.resultingStatus = resultingStatus;
            this.resultingAssignment = resultingAssignment;
            this.returnsFalseWhenInvalid = returnsFalseWhenInvalid;
            this.call = call;
        }
    }

    /** Precondiciones válidas de cada operación: LoadStatus requerido y AssignmentStatus admitidos. */
    private static boolean isValid(LoadStatus status, AssignmentStatus assignment, Operation operation) {
        return switch (operation) {
            case ASSIGN -> status == LoadStatus.AVAILABLE
                    && (assignment == null || assignment == AssignmentStatus.UNASSIGNED_NO_DRIVERS);
            case PROPOSE_REASSIGNMENT -> status == LoadStatus.AVAILABLE
                    && (assignment == AssignmentStatus.REJECTED || assignment == AssignmentStatus.TIMEOUT);
            case NOTIFY, APPLY_NOTIFIED -> status == LoadStatus.AVAILABLE && assignment == AssignmentStatus.PROPOSED;
            case ACCEPT, REJECT -> status == LoadStatus.AVAILABLE && assignment == AssignmentStatus.NOTIFIED;
            case TIMEOUT, EXPIRE_IF_UNANSWERED -> status == LoadStatus.AVAILABLE
                    && (assignment == AssignmentStatus.PROPOSED || assignment == AssignmentStatus.NOTIFIED);
            case MARK_NO_DRIVERS -> status == LoadStatus.AVAILABLE && (assignment == null
                    || assignment == AssignmentStatus.REJECTED || assignment == AssignmentStatus.TIMEOUT);
            case START_TRIP -> status == LoadStatus.ASSIGNED;
            case DELIVER -> status == LoadStatus.IN_TRANSIT;
            case CANCEL -> status == LoadStatus.AVAILABLE || status == LoadStatus.ASSIGNED;
        };
    }

    static Stream<Arguments> allCombinations() {
        List<AssignmentStatus> assignments = new ArrayList<>();
        assignments.add(null);
        assignments.addAll(EnumSet.allOf(AssignmentStatus.class));
        List<Arguments> combinations = new ArrayList<>();
        for (LoadStatus status : LoadStatus.values()) {
            for (AssignmentStatus assignment : assignments) {
                for (Operation operation : Operation.values()) {
                    String expected = isValid(status, assignment, operation)
                            ? operation.resultingStatus + " / " + (operation.resultingAssignment != null
                                    ? operation.resultingAssignment : assignment) + " (" + operation.event + ")"
                            : (operation.returnsFalseWhenInvalid ? "ignorada (false)" : "409");
                    combinations.add(Arguments.of(status, assignment, operation, expected));
                }
            }
        }
        return combinations.stream();
    }

    private FreightLoad givenLoad(LoadStatus status, AssignmentStatus assignment) {
        FreightLoad load = FreightLoad.builder()
                .id(LOAD_ID)
                .generatorCompanyId(GENERATOR)
                .status(status)
                .assignmentStatus(assignment)
                .assignedDriverId(DRIVER)
                .assignedDriverName("Actual")
                .assignmentAttempts(1)
                .assignmentStatusChangedAt(LocalDateTime.now().minusMinutes(10))
                .publishedAt(LocalDateTime.now().minusHours(1))
                .assignedAt(status == LoadStatus.AVAILABLE ? null : LocalDateTime.now().minusMinutes(30))
                .tripStartedAt(status == LoadStatus.IN_TRANSIT ? LocalDateTime.now().minusMinutes(5) : null)
                .build();
        when(repository.findById(LOAD_ID)).thenReturn(Optional.of(load));
        return load;
    }

    private static DeliveryConfirmationRequest pod() {
        return DeliveryConfirmationRequest.builder().receiverName("Jefe de bodega").receiverDocument("CC 123").build();
    }

    @ParameterizedTest(name = "{0} / {1} --{2}--> {3}")
    @MethodSource("allCombinations")
    void onlyDocumentedTransitionsSucceed(LoadStatus status, AssignmentStatus assignment, Operation operation,
                                          String expected) {
        FreightLoad load = givenLoad(status, assignment);

        if (isValid(status, assignment, operation)) {
            Object result = operation.call.apply(service, DRIVER);

            if (operation.returnsFalseWhenInvalid) {
                assertThat(result).as("la operación del motor se aplicó").isEqualTo(true);
            }
            assertThat(load.getStatus()).isEqualTo(operation.resultingStatus);
            assertThat(load.getAssignmentStatus())
                    .isEqualTo(operation.resultingAssignment != null ? operation.resultingAssignment : assignment);
            verify(outbox).enqueueLoadEvent(any(FreightLoad.class), eq(operation.event));
            if (operation == Operation.REJECT || operation == Operation.TIMEOUT
                    || operation == Operation.EXPIRE_IF_UNANSWERED) {
                assertThat(load.getDiscardedDriverIds()).as("el conductor queda descartado").contains(DRIVER);
            }
            return;
        }

        if (operation.returnsFalseWhenInvalid) {
            assertThat(operation.call.apply(service, DRIVER)).isEqualTo(false);
        } else {
            assertThatThrownBy(() -> operation.call.apply(service, DRIVER))
                    .isInstanceOf(InvalidLoadOperationException.class);
        }
        assertThat(load.getStatus()).isEqualTo(status);
        assertThat(load.getAssignmentStatus()).isEqualTo(assignment);
        assertThat(load.getDiscardedDriverIds()).isEmpty();
        verifyNoInteractions(outbox);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("La matriz genera todas las combinaciones (5 × 7 × 12 = 420)")
    void theMatrixCoversEveryCombination() {
        assertThat(allCombinations().count())
                .isEqualTo((long) LoadStatus.values().length * (AssignmentStatus.values().length + 1)
                        * Operation.values().length)
                .isGreaterThan(200);
    }

    @Nested
    @DisplayName("Identidad: solo el conductor asignado responde, inicia y entrega")
    class Identity {

        @ParameterizedTest(name = "{0} por otro usuario -> 403")
        @EnumSource(value = Operation.class, names = {"ACCEPT", "REJECT", "START_TRIP", "DELIVER"})
        void anotherUserIsForbidden(Operation operation) {
            FreightLoad load = givenLoad(validStatusFor(operation), validAssignmentFor(operation));

            assertThatThrownBy(() -> operation.call.apply(service, OTHER_USER))
                    .isInstanceOf(AssignmentAccessDeniedException.class)
                    .satisfies(e -> assertThat(((AssignmentAccessDeniedException) e).isUnauthenticated()).isFalse());
            assertThat(load.getStatus()).isEqualTo(validStatusFor(operation));
            verifyNoInteractions(outbox);
        }

        @ParameterizedTest(name = "{0} sin usuario -> 401")
        @EnumSource(value = Operation.class, names = {"ACCEPT", "REJECT", "START_TRIP", "DELIVER"})
        void noUserIsUnauthenticated(Operation operation) {
            givenLoad(validStatusFor(operation), validAssignmentFor(operation));

            assertThatThrownBy(() -> operation.call.apply(service, null))
                    .isInstanceOf(AssignmentAccessDeniedException.class)
                    .satisfies(e -> assertThat(((AssignmentAccessDeniedException) e).isUnauthenticated()).isTrue());
            verifyNoInteractions(outbox);
        }

        @Test
        @DisplayName("El generador dueño cancela; otro generador o un conductor no")
        void onlyTheOwningGeneratorOrTheControlTowerCancel() {
            FreightLoad load = givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.NOTIFIED);
            CancelLoadRequest reason = new CancelLoadRequest("Ya no se envía");

            assertThatThrownBy(() -> service.cancelLoad(LOAD_ID, "otro-generador", "GENERADOR", reason))
                    .isInstanceOf(AssignmentAccessDeniedException.class);
            assertThatThrownBy(() -> service.cancelLoad(LOAD_ID, DRIVER, "CONDUCTOR", reason))
                    .isInstanceOf(AssignmentAccessDeniedException.class);
            verifyNoInteractions(outbox);

            service.cancelLoad(LOAD_ID, GENERATOR, "GENERADOR", reason);
            assertThat(load.getStatus()).isEqualTo(LoadStatus.CANCELLED);
            assertThat(load.getCancelledBy()).isEqualTo(GENERATOR);
        }

        private LoadStatus validStatusFor(Operation operation) {
            return switch (operation) {
                case START_TRIP -> LoadStatus.ASSIGNED;
                case DELIVER -> LoadStatus.IN_TRANSIT;
                default -> LoadStatus.AVAILABLE;
            };
        }

        private AssignmentStatus validAssignmentFor(Operation operation) {
            return operation == Operation.ACCEPT || operation == Operation.REJECT
                    ? AssignmentStatus.NOTIFIED : AssignmentStatus.ACCEPTED;
        }
    }

    @Nested
    @DisplayName("Casos especiales")
    class SpecialCases {

        @Test
        @DisplayName("Una propuesta renovada después del corte no vence")
        void aProposalRenewedAfterTheCutoffDoesNotExpire() {
            FreightLoad load = givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.NOTIFIED);
            load.setAssignmentStatusChangedAt(LocalDateTime.now());

            assertThat(service.expireIfUnanswered(LOAD_ID, LocalDateTime.now().minusMinutes(5))).isFalse();
            assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.NOTIFIED);
            verifyNoInteractions(outbox);
        }

        @Test
        @DisplayName("Con los intentos agotados la reasignación deja la carga sin conductor")
        void exhaustedAttemptsLeaveTheLoadUnassigned() {
            FreightLoad load = givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.REJECTED);
            load.setAssignmentAttempts(5);

            assertThat(service.proposeReassignment(LOAD_ID, NEW_DRIVER, "Nuevo", "NEW-001")).isFalse();
            assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.UNASSIGNED_NO_DRIVERS);
            verify(outbox).enqueueLoadEvent(load, "assignment.unassigned");
        }

        @Test
        @DisplayName("No se reasigna a un conductor descartado")
        void aDiscardedDriverIsNotProposedAgain() {
            FreightLoad load = givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.TIMEOUT);
            load.getDiscardedDriverIds().add(NEW_DRIVER);

            assertThat(service.proposeReassignment(LOAD_ID, NEW_DRIVER, "Nuevo", "NEW-001")).isFalse();
            assertThatThrownBy(() -> service.assignDriver(LOAD_ID, new AssignDriverRequest(NEW_DRIVER, "Nuevo", "N")))
                    .isInstanceOf(InvalidLoadOperationException.class);
            verifyNoInteractions(outbox);
        }

        @Test
        @DisplayName("assignment.notified de otro conductor no avanza la propuesta vigente")
        void aNotificationForAnotherDriverIsIgnored() {
            FreightLoad load = givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.PROPOSED);

            assertThat(service.applyAssignmentNotified(LOAD_ID, "conductor-anterior")).isFalse();
            assertThat(load.getAssignmentStatus()).isEqualTo(AssignmentStatus.PROPOSED);
            verifyNoInteractions(outbox);
        }

        @Test
        @DisplayName("Aceptar con otra carga en curso es un 409")
        void acceptingWhileBusyWithAnotherLoadIsAConflict() {
            givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.NOTIFIED);
            when(repository.findByAssignedDriverIdAndStatusIn(eq(DRIVER), any()))
                    .thenReturn(List.of(FreightLoad.builder().id(99L).status(LoadStatus.IN_TRANSIT).build()));

            assertThatThrownBy(() -> service.acceptAssignment(LOAD_ID, DRIVER))
                    .isInstanceOf(InvalidLoadOperationException.class)
                    .hasMessageContaining("#99");
            verifyNoInteractions(outbox);
        }

        /**
         * Concurrencia: el scheduler y un accept/reject pueden tocar la misma carga a la vez. @Version hace fallar al
         * segundo con ObjectOptimisticLockingFailureException; el scheduler lo registra y sigue con las demás cargas.
         */
        @Test
        @DisplayName("Un conflicto de @Version en el scheduler no detiene el resto de vencimientos")
        void anOptimisticLockConflictDoesNotStopTheScheduler() {
            IFreightLoadService loads = mock(IFreightLoadService.class);
            FreightLoad contended = FreightLoad.builder().id(1L).build();
            FreightLoad other = FreightLoad.builder().id(2L).build();
            when(repository.findByAssignmentStatusAndAssignmentStatusChangedAtBefore(eq(AssignmentStatus.NOTIFIED), any()))
                    .thenReturn(List.of(contended, other));
            doThrow(new ObjectOptimisticLockingFailureException(FreightLoad.class, 1L))
                    .when(loads).expireIfUnanswered(eq(1L), any());

            new AssignmentTimeoutScheduler(repository, loads).expireUnansweredAssignments();

            verify(loads).expireIfUnanswered(eq(1L), any());
            verify(loads).expireIfUnanswered(eq(2L), any());
        }

        @Test
        @DisplayName("Una carga inexistente responde 404, no 409")
        void anUnknownLoadIsNotFound() {
            when(repository.findById(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.notifyAssignment(LOAD_ID))
                    .isInstanceOf(escuelaing.edu.co.truckcar.load_allocation.exception.FreightLoadNotFoundException.class);
            assertThat(service.markNoDriversAvailable(LOAD_ID)).isFalse();
            assertThat(service.proposeReassignment(LOAD_ID, NEW_DRIVER, "Nuevo", "N")).isFalse();
            assertThat(service.applyAssignmentNotified(LOAD_ID, DRIVER)).isFalse();
            assertThat(service.expireIfUnanswered(LOAD_ID, LocalDateTime.now())).isFalse();
            verifyNoInteractions(outbox);
        }
    }

    @Nested
    @DisplayName("Bordes: identidad vacía, consultas y evidencias")
    class Edges {

        @ParameterizedTest(name = "usuario en blanco [{0}] -> 401")
        @ValueSource(strings = {"", "  "})
        void aBlankUserIsUnauthenticatedEverywhere(String blank) {
            givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.NOTIFIED);
            CancelLoadRequest reason = new CancelLoadRequest("x");

            for (Runnable call : List.<Runnable>of(
                    () -> service.acceptAssignment(LOAD_ID, blank),
                    () -> service.cancelLoad(LOAD_ID, blank, "COORDINADOR", reason),
                    () -> service.getLoadsForDriver(blank),
                    () -> service.getProofOfDelivery(LOAD_ID, blank, "ADMIN"),
                    () -> service.listLoads(null, blank, "ADMIN"),
                    () -> service.getLoadsByCompany(GENERATOR, blank, "ADMIN"),
                    () -> service.getPublishedLoads(blank, "GENERADOR"))) {
                assertThatThrownBy(call::run).isInstanceOf(AssignmentAccessDeniedException.class)
                        .satisfies(e -> assertThat(((AssignmentAccessDeniedException) e).isUnauthenticated()).isTrue());
            }
            assertThatThrownBy(() -> service.listLoads(null, null, "ADMIN"))
                    .isInstanceOf(AssignmentAccessDeniedException.class);
            assertThatThrownBy(() -> service.getLoadsByCompany(GENERATOR, null, "ADMIN"))
                    .isInstanceOf(AssignmentAccessDeniedException.class);
            verifyNoInteractions(outbox);
        }

        @Test
        @DisplayName("Listar todas las cargas: solo torre de control, con o sin filtro de status")
        void listLoadsIsForTheControlTowerOnly() {
            FreightLoad any = givenLoad(LoadStatus.AVAILABLE, null);
            when(repository.findTop200ByOrderByPublishedAtDesc()).thenReturn(List.of(any));
            when(repository.findTop200ByStatusInOrderByPublishedAtDesc(any())).thenReturn(List.of());

            assertThatThrownBy(() -> service.listLoads(null, "u", null)).isInstanceOf(AssignmentAccessDeniedException.class);
            assertThatThrownBy(() -> service.listLoads(null, "u", "CONDUCTOR"))
                    .isInstanceOf(AssignmentAccessDeniedException.class);
            assertThat(service.listLoads(null, "u", "ADMIN")).hasSize(1);
            assertThat(service.listLoads(List.of(), "u", "COORDINADOR")).hasSize(1);
            assertThat(service.listLoads(List.of(LoadStatus.DELIVERED), "u", "COORDINADOR")).isEmpty();
        }

        @Test
        @DisplayName("Envíos publicados y bolsa de cargas según el rol")
        void publishedLoadsAndTheLoadBoardDependOnTheRole() {
            FreightLoad own = givenLoad(LoadStatus.AVAILABLE, null);
            when(repository.findByGeneratorCompanyIdOrderByPublishedAtDesc(GENERATOR)).thenReturn(List.of(own));

            assertThatThrownBy(() -> service.getPublishedLoads(GENERATOR, null))
                    .isInstanceOf(AssignmentAccessDeniedException.class);
            assertThatThrownBy(() -> service.getPublishedLoads(GENERATOR, "CONDUCTOR"))
                    .isInstanceOf(AssignmentAccessDeniedException.class);
            assertThat(service.getPublishedLoads(GENERATOR, "GENERADOR")).hasSize(1);
            assertThat(service.getLoadsByCompany(GENERATOR, "admin-1", "ADMIN")).hasSize(1);

            service.getAvailableLoads(null, "COORDINADOR");
            service.getAvailableLoads(TruckTypeRequired.TURBO, null);
            verify(repository).findByStatusOrderByPublishedAtDesc(LoadStatus.AVAILABLE);
            verify(repository).findByStatusAndRequiredTruckType(LoadStatus.AVAILABLE, TruckTypeRequired.TURBO);
        }

        @Test
        @DisplayName("/mine: propuestas vigentes, aceptadas y en ruta; no las vencidas, entregadas ni canceladas")
        void myLoadsOnlyShowWhatTheDriverMustAttend() {
            List<FreightLoad> loads = new ArrayList<>();
            long id = 10;
            for (LoadStatus status : LoadStatus.values()) {
                for (AssignmentStatus assignment : AssignmentStatus.values()) {
                    loads.add(FreightLoad.builder().id(id++).status(status).assignmentStatus(assignment).build());
                }
            }
            when(repository.findByAssignedDriverIdOrderByAssignedAtDesc(DRIVER)).thenReturn(loads);

            assertThat(service.getLoadsForDriver(DRIVER))
                    .hasSize(2 * AssignmentStatus.values().length + 2)
                    .allSatisfy(load -> assertThat(load.getStatus() == LoadStatus.ASSIGNED
                            || load.getStatus() == LoadStatus.IN_TRANSIT
                            || (load.getStatus() == LoadStatus.AVAILABLE
                            && (load.getAssignmentStatus() == AssignmentStatus.PROPOSED
                            || load.getAssignmentStatus() == AssignmentStatus.NOTIFIED))).isTrue());
        }

        @ParameterizedTest(name = "subir evidencias con la carga {0}")
        @EnumSource(LoadStatus.class)
        void proofCanOnlyBeUploadedForAssignedOrInTransitLoads(LoadStatus status) {
            givenLoad(status, AssignmentStatus.ACCEPTED);

            if (status == LoadStatus.ASSIGNED || status == LoadStatus.IN_TRANSIT) {
                service.requireDriverCanUploadProof(LOAD_ID, DRIVER);
            } else {
                assertThatThrownBy(() -> service.requireDriverCanUploadProof(LOAD_ID, DRIVER))
                        .isInstanceOf(InvalidLoadOperationException.class);
            }
        }

        @Test
        @DisplayName("Entrega con firma y foto guarda las referencias; una clave de otra carga es un 409")
        void deliveryWithSignatureAndPhotoStoresTheirReferences() {
            FreightLoad load = givenLoad(LoadStatus.IN_TRANSIT, AssignmentStatus.ACCEPTED);
            PodObjectReference signature = PodObjectReference.builder()
                    .objectKey("pod/1/signature-abc.png").sha256("abc").contentType("image/png").sizeBytes(10L).build();
            PodObjectReference photo = PodObjectReference.builder()
                    .objectKey("pod/1/photo-def.jpg").sha256("def").contentType("image/jpeg").sizeBytes(20L).build();
            PodObjectReference foreign = PodObjectReference.builder().objectKey("pod/2/photo-xyz.jpg").build();

            assertThatThrownBy(() -> service.deliverLoad(LOAD_ID, DRIVER, DeliveryConfirmationRequest.builder()
                    .receiverName("Bodega").photo(foreign).build())).isInstanceOf(InvalidLoadOperationException.class);
            assertThat(load.getStatus()).isEqualTo(LoadStatus.IN_TRANSIT);

            service.deliverLoad(LOAD_ID, DRIVER, DeliveryConfirmationRequest.builder()
                    .receiverName("Bodega").signature(signature).photo(photo).build());
            assertThat(load.getPodHasSignature()).isTrue();
            assertThat(load.getPodHasPhoto()).isTrue();

            FreightLoad onlySignature = givenLoad(LoadStatus.IN_TRANSIT, AssignmentStatus.ACCEPTED);
            service.deliverLoad(LOAD_ID, DRIVER, DeliveryConfirmationRequest.builder()
                    .receiverName("Bodega").signature(signature).build());
            assertThat(onlySignature.getPodHasPhoto()).isFalse();
        }

        @Test
        @DisplayName("POD: solo el conductor, el generador dueño o la torre, y solo de una carga entregada")
        void proofOfDeliveryAccess() {
            givenLoad(LoadStatus.IN_TRANSIT, AssignmentStatus.ACCEPTED);

            assertThatThrownBy(() -> service.getProofOfDelivery(LOAD_ID, "intruso", "CONDUCTOR"))
                    .isInstanceOf(AssignmentAccessDeniedException.class);
            assertThatThrownBy(() -> service.getProofOfDelivery(LOAD_ID, GENERATOR, "GENERADOR"))
                    .isInstanceOf(InvalidLoadOperationException.class);
        }

        @Test
        @DisplayName("Publicar: el generador es quien publica; una llamada interna sin usuario debe indicarlo")
        void publishingResolvesTheGeneratorFromTheCaller() {
            CreateFreightLoadRequest request = CreateFreightLoadRequest.builder()
                    .generatorCompanyId("empresa-del-cuerpo").generatorCompanyName("E").cargoDescription("x")
                    .weightTons(1.0).requiredTruckType(TruckTypeRequired.TURBO).build();

            assertThat(service.publishLoad(request, null, GENERATOR, "GENERADOR").getGeneratorCompanyId())
                    .isEqualTo(GENERATOR);
            assertThat(service.publishLoad(request, null, null, null).getGeneratorCompanyId())
                    .isEqualTo("empresa-del-cuerpo");
            assertThat(service.publishLoad(request, null, " ", null).getGeneratorCompanyId())
                    .isEqualTo("empresa-del-cuerpo");
            assertThatThrownBy(() -> service.publishLoad(request, null, DRIVER, null))
                    .isInstanceOf(AssignmentAccessDeniedException.class);
            assertThatThrownBy(() -> service.publishLoad(request, null, DRIVER, "CONDUCTOR"))
                    .isInstanceOf(AssignmentAccessDeniedException.class);
            request.setGeneratorCompanyId(null);
            assertThatThrownBy(() -> service.publishLoad(request, null, null, null))
                    .isInstanceOf(AssignmentAccessDeniedException.class);
            request.setGeneratorCompanyId(" ");
            assertThatThrownBy(() -> service.publishLoad(request, null, null, null))
                    .isInstanceOf(AssignmentAccessDeniedException.class);
        }

        @Test
        @DisplayName("Horas offline fuera de la cronología se acotan entre el hito anterior y ahora")
        void offlineTimesAreClampedToTheTimeline() {
            LocalDateTime now = LocalDateTime.now();
            assertThat(FreightLoadService.withinTimeline(null, now.minusHours(1), now)).isEqualTo(now);
            assertThat(FreightLoadService.withinTimeline(now.plusHours(1), null, now)).isEqualTo(now);
            assertThat(FreightLoadService.withinTimeline(now.minusHours(2), now.minusHours(1), now))
                    .isEqualTo(now.minusHours(1));
            assertThat(FreightLoadService.withinTimeline(now.minusHours(2), now.plusHours(1), now)).isEqualTo(now);
            assertThat(FreightLoadService.withinTimeline(now.minusMinutes(5), now.minusHours(1), now))
                    .isEqualTo(now.minusMinutes(5));

            FreightLoad load = givenLoad(LoadStatus.ASSIGNED, AssignmentStatus.ACCEPTED);
            service.startTrip(LOAD_ID, DRIVER, now.minusDays(3));
            assertThat(load.getTripStartedAt()).isEqualTo(load.getAssignedAt());
        }

        @Test
        @DisplayName("Datos incompletos del motor o de cargas antiguas no rompen el ciclo")
        void incompleteDataDoesNotBreakTheCycle() {
            FreightLoad noTimestamp = givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.NOTIFIED);
            noTimestamp.setAssignmentStatusChangedAt(null);
            assertThat(service.expireIfUnanswered(LOAD_ID, LocalDateTime.now())).isFalse();

            givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.REJECTED);
            assertThat(service.proposeReassignment(LOAD_ID, null, "Sin id", "X")).isFalse();

            FreightLoad anyDriver = givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.PROPOSED);
            assertThat(service.applyAssignmentNotified(LOAD_ID, null)).isTrue();
            assertThat(anyDriver.getAssignmentStatus()).isEqualTo(AssignmentStatus.NOTIFIED);

            FreightLoad legacy = givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.PROPOSED);
            legacy.setAssignedDriverId(null);
            service.timeoutAssignment(LOAD_ID);
            assertThat(legacy.getDiscardedDriverIds()).isEmpty();

            FreightLoad reopened = givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.TIMEOUT);
            reopened.setAssignmentAttempts(null);
            assertThat(service.proposeReassignment(LOAD_ID, NEW_DRIVER, "Nuevo", "N")).isTrue();
            assertThat(reopened.getAssignmentAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("Asignar a un conductor ocupado en otra carga es un 409; la misma carga no cuenta")
        void assigningABusyDriverIsAConflict() {
            givenLoad(LoadStatus.AVAILABLE, null);
            when(repository.findDriverCommitments(any(), any(), any())).thenReturn(List.of(
                    FreightLoad.builder().id(LOAD_ID).assignedDriverId(NEW_DRIVER).status(LoadStatus.AVAILABLE).build(),
                    FreightLoad.builder().id(7L).assignedDriverId("otro").status(LoadStatus.IN_TRANSIT).build(),
                    FreightLoad.builder().id(8L).assignedDriverId(NEW_DRIVER).status(LoadStatus.AVAILABLE)
                            .assignmentStatus(AssignmentStatus.NOTIFIED).build()));

            assertThatThrownBy(() -> service.assignDriver(LOAD_ID, new AssignDriverRequest(NEW_DRIVER, "N", "P")))
                    .isInstanceOf(InvalidLoadOperationException.class)
                    .hasMessageContaining("#8").hasMessageContaining("propuesta NOTIFIED");
            assertThat(service.getDriverCommitments("COORDINADOR")).hasSize(3);

            when(repository.findByAssignedDriverIdAndStatusIn(eq(DRIVER), any())).thenReturn(List.of(
                    FreightLoad.builder().id(LOAD_ID).status(LoadStatus.ASSIGNED).build()));
            givenLoad(LoadStatus.AVAILABLE, AssignmentStatus.NOTIFIED);
            service.acceptAssignment(LOAD_ID, DRIVER);
        }
    }
}
