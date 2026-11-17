package escuelaing.edu.co.truckcar.load_allocation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckcar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckcar.load_allocation.listener.UserAccountEventListener;
import escuelaing.edu.co.truckcar.load_allocation.model.DriverAccountStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.DriverAffiliation;
import escuelaing.edu.co.truckcar.load_allocation.repository.DriverAccountStatusRepository;
import escuelaing.edu.co.truckcar.load_allocation.repository.DriverAffiliationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Lote 2: solo una cuenta ACTIVE acepta cargas; el estado llega por los eventos de user-identity. */
class DriverAccountGuardTest {

    private final Map<String, DriverAccountStatus> table = new HashMap<>();
    private DriverAccountStatusRepository repository;
    private DriverAccountGuard guard;
    private UserAccountEventListener listener;

    @BeforeEach
    void setUp() {
        repository = mock(DriverAccountStatusRepository.class);
        when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(table.get(i.<String>getArgument(0))));
        when(repository.existsById(anyString())).thenAnswer(i -> table.containsKey(i.<String>getArgument(0)));
        when(repository.save(any(DriverAccountStatus.class))).thenAnswer(i -> {
            DriverAccountStatus s = i.getArgument(0);
            table.put(s.getDriverId(), s);
            return s;
        });
        affiliationRepository = mock(DriverAffiliationRepository.class);
        when(affiliationRepository.findById(anyString()))
                .thenAnswer(i -> Optional.ofNullable(affiliationTable.get(i.<String>getArgument(0))));
        when(affiliationRepository.save(any(DriverAffiliation.class))).thenAnswer(i -> {
            DriverAffiliation a = i.getArgument(0);
            affiliationTable.put(a.getDriverId(), a);
            return a;
        });
        guard = new DriverAccountGuard(repository, affiliationRepository);
        listener = new UserAccountEventListener(repository, affiliationRepository, new ObjectMapper());
    }

    private final Map<String, DriverAffiliation> affiliationTable = new HashMap<>();
    private DriverAffiliationRepository affiliationRepository;

    private void affiliation(String userId, String driverType, String status, boolean eligible) {
        event("user.affiliation.changed", userId, ",\"driverType\":\"" + driverType + "\",\"companyId\":"
                + ("EMPRESA".equals(driverType) ? "\"c-1\",\"companyName\":\"Transportes Andinos\"" : "null")
                + ",\"affiliationStatus\":" + (status == null ? "null" : "\"" + status + "\"")
                + ",\"loadEligible\":" + eligible);
    }

    @Test
    void aVerifiedIndependentDriverCannotAcceptLoadsAndIsToldWhy() {
        event("user.status.changed", "ivan", ",\"status\":\"ACTIVE\"");
        affiliation("ivan", "INDEPENDIENTE", null, false);

        assertThatThrownBy(() -> guard.requireVerified("ivan")).isInstanceOf(AssignmentAccessDeniedException.class)
                .hasMessageContaining("independiente");
        assertThat(affiliationTable.get("ivan").isLoadEligible()).isFalse();
    }

    @Test
    void aCompanyDriverAcceptsOnlyOnceTheCompanyApprovesTheAffiliation() {
        event("user.status.changed", "carla", ",\"status\":\"ACTIVE\"");
        affiliation("carla", "EMPRESA", "PENDIENTE", false);
        assertThatThrownBy(() -> guard.requireVerified("carla")).hasMessageContaining("aún no aprueba");

        affiliation("carla", "EMPRESA", "RECHAZADA", false);
        assertThatThrownBy(() -> guard.requireVerified("carla")).hasMessageContaining("rechazó");

        affiliation("carla", "EMPRESA", "ACTIVA", true);
        assertThatCode(() -> guard.requireVerified("carla")).doesNotThrowAnyException();
        assertThat(affiliationTable.get("carla").getCompanyName()).isEqualTo("Transportes Andinos");
    }

    @Test
    void theAffiliationNeverReplacesTheAccountVerification() {
        affiliation("pablo", "EMPRESA", "ACTIVA", true);
        event("user.created", "pablo", ",\"role\":\"CONDUCTOR\"");

        assertThatThrownBy(() -> guard.requireVerified("pablo")).hasMessageContaining("aún no ha sido verificada");
    }

    private void event(String type, String userId, String extra) {
        listener.handleUserEvent("{\"eventType\":\"" + type + "\",\"payload\":{\"userId\":\"" + userId + "\"" + extra + "}}");
    }

    @Test
    void aNewDriverIsPendingAndCannotAcceptUntilAnAdminVerifiesTheAccount() {
        event("user.created", "ana", ",\"role\":\"CONDUCTOR\"");

        assertThatThrownBy(() -> guard.requireVerified("ana")).isInstanceOf(AssignmentAccessDeniedException.class)
                .hasMessageContaining("aún no ha sido verificada")
                .satisfies(e -> assertThat(((AssignmentAccessDeniedException) e).isUnauthenticated()).isFalse());

        event("user.status.changed", "ana", ",\"status\":\"ACTIVE\"");
        assertThatCode(() -> guard.requireVerified("ana")).doesNotThrowAnyException();
    }

    @Test
    void aSuspendedAccountCannotAccept() {
        event("user.status.changed", "luis", ",\"status\":\"SUSPENDED\"");

        assertThatThrownBy(() -> guard.requireVerified("luis")).hasMessageContaining("suspendida");
    }

    @Test
    void anUnknownDriverFailsClosed() {
        assertThatThrownBy(() -> guard.requireVerified("desconocido")).hasMessageContaining("no se pudo confirmar");
    }

    @Test
    void aLateUserCreatedDoesNotOverwriteAVerificationThatArrivedFirst() {
        event("user.status.changed", "sofia", ",\"status\":\"ACTIVE\"");
        event("user.created", "sofia", ",\"role\":\"CONDUCTOR\"");

        assertThat(table.get("sofia").getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void generatorsInvalidJsonAndEventsWithoutUserAreIgnored() {
        event("user.created", "gen", ",\"role\":\"GENERADOR\"");
        listener.handleUserEvent("no es json");
        listener.handleUserEvent("{\"eventType\":\"user.status.changed\",\"payload\":{\"status\":\"ACTIVE\"}}");
        event("user.status.changed", "x", "");

        assertThat(table).isEmpty();
    }
}
