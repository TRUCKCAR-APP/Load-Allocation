package escuelaing.edu.co.truckcar.load_allocation.tracking;

import escuelaing.edu.co.truckcar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckcar.load_allocation.exception.InvalidLoadOperationException;
import escuelaing.edu.co.truckcar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckcar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckcar.load_allocation.repository.FreightLoadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicTrackingServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T15:00:00Z");

    private FreightLoadRepository repository;
    private FleetPositionClient fleet;
    private TrackingProperties properties;
    private TrackingTokenService tokens;
    private FreightLoad load;

    @BeforeEach
    void setUp() {
        repository = mock(FreightLoadRepository.class);
        fleet = mock(FleetPositionClient.class);
        properties = new TrackingProperties();
        properties.setSecret("clave-de-pruebas-de-al-menos-32-bytes!!");
        properties.setPublicBaseUrl("https://truckcar.example.com/");
        tokens = new TrackingTokenService(properties);
        load = FreightLoad.builder().id(30L).generatorCompanyId("gen-1").cargoDescription("Café verde").weightTons(28.0)
                .originCity("Bogotá").originDepartment("Cundinamarca").destinationCity("Cali").destinationDepartment("Valle")
                .status(LoadStatus.IN_TRANSIT).assignedDriverId("driver-ana").assignedVehiclePlate("TDK-410")
                .publishedAt(local(NOW.minus(Duration.ofHours(5)))).tripStartedAt(local(NOW.minus(Duration.ofHours(1))))
                .build();
        when(repository.findById(30L)).thenReturn(Optional.of(load));
    }

    private PublicTrackingService serviceAt(Instant instant) {
        return new PublicTrackingService(repository, tokens, fleet, properties, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    @Test
    void theOwningGeneratorGetsALinkThatLastsAtMost48Hours() {
        TrackingLinkResponse link = serviceAt(NOW).createLink(30L, "gen-1", "GENERADOR");

        assertThat(link.url()).startsWith("https://truckcar.example.com/?track=").endsWith(link.token());
        assertThat(link.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(48)));
    }

    @Test
    void otherGeneratorsAndDriversCannotShareTheLoad() {
        assertThatThrownBy(() -> serviceAt(NOW).createLink(30L, "gen-2", "GENERADOR"))
                .isInstanceOf(AssignmentAccessDeniedException.class);
        assertThatThrownBy(() -> serviceAt(NOW).createLink(30L, "driver-ana", "CONDUCTOR"))
                .isInstanceOf(AssignmentAccessDeniedException.class);
        assertThat(serviceAt(NOW).createLink(30L, "coord-1", "COORDINADOR").token()).isNotBlank();
    }

    @Test
    void aCancelledLoadCannotBeShared() {
        load.setStatus(LoadStatus.CANCELLED);

        assertThatThrownBy(() -> serviceAt(NOW).createLink(30L, "gen-1", "GENERADOR"))
                .isInstanceOf(InvalidLoadOperationException.class);
    }

    @Test
    void thePublicViewShowsTheLivePositionWithoutPersonalData() {
        when(fleet.latest("driver-ana")).thenReturn(Optional.of(
                new FleetPositionClient.Position(4.5, -75.2, 62.0, 210.0, "2026-10-31T09:59:00", 30L)));
        String token = serviceAt(NOW).createLink(30L, "gen-1", "GENERADOR").token();

        PublicTrackingResponse view = serviceAt(NOW.plus(Duration.ofHours(2))).view(token);

        assertThat(view.reference()).isEqualTo("CARGA-30");
        assertThat(view.statusLabel()).isEqualTo("En ruta");
        assertThat(view.vehiclePlate()).isEqualTo("TDK-•••");
        assertThat(view.position().latitude()).isEqualTo(4.5);
        assertThat(view.origin().city()).isEqualTo("Bogotá");
    }

    @Test
    void thePositionOfAnotherTripOfTheSameDriverIsNeverShown() {
        when(fleet.latest("driver-ana")).thenReturn(Optional.of(
                new FleetPositionClient.Position(6.2, -75.5, 40.0, null, null, 99L)));
        String token = serviceAt(NOW).createLink(30L, "gen-1", "GENERADOR").token();

        assertThat(serviceAt(NOW).view(token).position()).isNull();
    }

    @Test
    void beforeTheTripStartsThereIsNoPositionLookup() {
        load.setStatus(LoadStatus.ASSIGNED);
        String token = serviceAt(NOW).createLink(30L, "gen-1", "GENERADOR").token();

        assertThat(serviceAt(NOW).view(token).position()).isNull();
        verify(fleet, never()).latest(anyString());
    }

    @Test
    void theLinkEndsSixHoursAfterDeliveryAndWhenItExpires() {
        String token = serviceAt(NOW).createLink(30L, "gen-1", "GENERADOR").token();
        load.setStatus(LoadStatus.DELIVERED);
        load.setDeliveredAt(local(NOW.plus(Duration.ofHours(1))));

        assertThat(serviceAt(NOW.plus(Duration.ofHours(6))).view(token).statusLabel()).isEqualTo("Entregado");
        assertThatThrownBy(() -> serviceAt(NOW.plus(Duration.ofHours(8))).view(token))
                .isInstanceOf(TrackingLinkUnavailableException.class);

        load.setStatus(LoadStatus.IN_TRANSIT);
        load.setDeliveredAt(null);
        assertThatThrownBy(() -> serviceAt(NOW.plus(Duration.ofHours(49))).view(token))
                .isInstanceOf(TrackingLinkUnavailableException.class);
    }

    @Test
    void aTamperedTokenIsRejected() {
        String token = serviceAt(NOW).createLink(30L, "gen-1", "GENERADOR").token();
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertThatThrownBy(() -> serviceAt(NOW).view(tampered)).isInstanceOf(TrackingLinkUnavailableException.class);
        assertThatThrownBy(() -> serviceAt(NOW).view("no-es-un-token")).isInstanceOf(TrackingLinkUnavailableException.class);
    }
}
