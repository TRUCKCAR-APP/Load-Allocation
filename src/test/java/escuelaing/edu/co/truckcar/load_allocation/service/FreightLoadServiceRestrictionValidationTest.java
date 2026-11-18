package escuelaing.edu.co.truckcar.load_allocation.service;

import escuelaing.edu.co.truckcar.load_allocation.exception.RouteIncompatibleException;
import escuelaing.edu.co.truckcar.load_allocation.exception.RoutingServiceUnavailableException;
import escuelaing.edu.co.truckcar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckcar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckcar.load_allocation.repository.FreightLoadRepository;
import escuelaing.edu.co.truckcar.load_allocation.repository.ProofOfDeliveryMediaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pruebas de integración interna: FreightLoadService + VehicleRestrictionValidationService (mockeado).
 *
 * Verifica que:
 *  - startTrip con camión incompatible → RouteIncompatibleException (409)
 *  - startTrip con camión válido       → IN_TRANSIT
 *  - startTrip con servicio caído      → RoutingServiceUnavailableException (409)
 *  - acceptAssignment con camión incompat. → RouteIncompatibleException
 *  - sin restrictionValidator (null)   → startTrip y acceptAssignment funcionan sin validación
 */
@ExtendWith(MockitoExtension.class)
class FreightLoadServiceRestrictionValidationTest {

    private static final String DRIVER = "driver-carlos";
    private static final long   LOAD_ID = 42L;

    @Mock FreightLoadRepository              repository;
    @Mock OutboxEventService                 outboxEventService;
    @Mock ProofOfDeliveryMediaRepository     podMediaRepository;
    @Mock VehicleRestrictionValidationService restrictionValidator;

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private FreightLoad assignedLoad() {
        return FreightLoad.builder()
                .id(LOAD_ID)
                .status(LoadStatus.ASSIGNED)
                .assignmentStatus(AssignmentStatus.ACCEPTED)
                .assignedDriverId(DRIVER)
                .assignedVehiclePlate("TRK-001")
                .originCity("Bogotá")
                .destinationCity("Medellín")
                .weightTons(15.0)
                .build();
    }

    private FreightLoad availableNotifiedLoad() {
        return FreightLoad.builder()
                .id(LOAD_ID)
                .status(LoadStatus.AVAILABLE)
                .assignmentStatus(AssignmentStatus.NOTIFIED)
                .assignedDriverId(DRIVER)
                .assignedVehiclePlate("TRK-001")
                .originCity("Cali")
                .destinationCity("Pasto")
                .weightTons(20.0)
                .build();
    }

    private FreightLoadService serviceWithValidator() {
        return new FreightLoadService(repository, outboxEventService, podMediaRepository, restrictionValidator);
    }

    private FreightLoadService serviceWithoutValidator() {
        return new FreightLoadService(repository, outboxEventService, podMediaRepository);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // startTrip — camión INCOMPATIBLE → excepción antes de cambiar el estado
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void startTrip_withIncompatibleVehicle_throwsRouteIncompatibleException() {
        FreightLoad load = assignedLoad();
        when(repository.findById(LOAD_ID)).thenReturn(Optional.of(load));
        doThrow(new RouteIncompatibleException(
                "Vehículo no apto para el corredor Bogotá-Medellín",
                List.of("GALIBO_ALTURA: vehículo 4.60 m supera gálibo 4.20 m")))
                .when(restrictionValidator).validateCompatibility(any(), eq(DRIVER));

        FreightLoadService service = serviceWithValidator();

        assertThatThrownBy(() -> service.startTrip(LOAD_ID, DRIVER))
                .isInstanceOf(RouteIncompatibleException.class)
                .satisfies(ex -> {
                    RouteIncompatibleException rie = (RouteIncompatibleException) ex;
                    assertThat(rie.getBlockingReasons()).hasSize(1);
                    assertThat(rie.getBlockingReasons().get(0)).contains("GALIBO_ALTURA");
                });

        // La carga NO debe haber cambiado de estado
        assertThat(load.getStatus()).isEqualTo(LoadStatus.ASSIGNED);
        verify(repository, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // startTrip — camión COMPATIBLE → IN_TRANSIT
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void startTrip_withCompatibleVehicle_transitionsToInTransit() {
        FreightLoad load = assignedLoad();
        when(repository.findById(LOAD_ID)).thenReturn(Optional.of(load));
        when(repository.save(any(FreightLoad.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(restrictionValidator).validateCompatibility(any(), any());

        FreightLoadService service = serviceWithValidator();
        service.startTrip(LOAD_ID, DRIVER);

        assertThat(load.getStatus()).isEqualTo(LoadStatus.IN_TRANSIT);
        assertThat(load.getTripStartedAt()).isNotNull();
        verify(restrictionValidator).validateCompatibility(any(), eq(DRIVER));
        verify(repository).save(load);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // startTrip — routing-restrictions CAÍDO → RoutingServiceUnavailableException
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void startTrip_whenRoutingServiceIsDown_throwsRoutingServiceUnavailableException() {
        FreightLoad load = assignedLoad();
        when(repository.findById(LOAD_ID)).thenReturn(Optional.of(load));
        doThrow(new RoutingServiceUnavailableException("ms-routing-restrictions no respondió", null))
                .when(restrictionValidator).validateCompatibility(any(), eq(DRIVER));

        FreightLoadService service = serviceWithValidator();

        assertThatThrownBy(() -> service.startTrip(LOAD_ID, DRIVER))
                .isInstanceOf(RoutingServiceUnavailableException.class)
                .hasMessageContaining("routing-restrictions");

        assertThat(load.getStatus()).isEqualTo(LoadStatus.ASSIGNED);
        verify(repository, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // acceptAssignment — camión INCOMPATIBLE antes de ACCEPTED → excepción
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void acceptAssignment_withIncompatibleVehicle_throwsRouteIncompatibleException() {
        FreightLoad load = availableNotifiedLoad();
        when(repository.findById(LOAD_ID)).thenReturn(Optional.of(load));
        // No hay otras cargas activas para este conductor
        when(repository.findByAssignedDriverIdAndStatusIn(eq(DRIVER), any())).thenReturn(List.of());

        doThrow(new RouteIncompatibleException(
                "Peso bruto supera el límite del corredor",
                List.of("PESO_BRUTO: 42.00 Tn supera el límite 32.00 Tn")))
                .when(restrictionValidator).validateCompatibility(any(), eq(DRIVER));

        FreightLoadService service = serviceWithValidator();

        assertThatThrownBy(() -> service.acceptAssignment(LOAD_ID, DRIVER))
                .isInstanceOf(RouteIncompatibleException.class)
                .satisfies(ex -> assertThat(((RouteIncompatibleException) ex).getBlockingReasons())
                        .anyMatch(r -> r.contains("PESO_BRUTO")));

        // El estado no debe haber avanzado
        assertThat(load.getStatus()).isEqualTo(LoadStatus.AVAILABLE);
        verify(repository, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sin validator (null) → startTrip y acceptAssignment funcionan igualmente
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void startTrip_withoutValidator_completesNormally() {
        FreightLoad load = assignedLoad();
        when(repository.findById(LOAD_ID)).thenReturn(Optional.of(load));
        when(repository.save(any(FreightLoad.class))).thenAnswer(inv -> inv.getArgument(0));

        FreightLoadService service = serviceWithoutValidator();
        service.startTrip(LOAD_ID, DRIVER);

        assertThat(load.getStatus()).isEqualTo(LoadStatus.IN_TRANSIT);
        verifyNoInteractions(restrictionValidator);
    }

    @Test
    void acceptAssignment_withoutValidator_completesNormally() {
        FreightLoad load = availableNotifiedLoad();
        when(repository.findById(LOAD_ID)).thenReturn(Optional.of(load));
        when(repository.findByAssignedDriverIdAndStatusIn(eq(DRIVER), any())).thenReturn(List.of());
        when(repository.save(any(FreightLoad.class))).thenAnswer(inv -> inv.getArgument(0));

        FreightLoadService service = serviceWithoutValidator();
        service.acceptAssignment(LOAD_ID, DRIVER);

        assertThat(load.getStatus()).isEqualTo(LoadStatus.ASSIGNED);
        verifyNoInteractions(restrictionValidator);
    }
}

