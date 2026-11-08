package escuelaing.edu.co.truckcar.load_allocation.service;

import escuelaing.edu.co.truckcar.load_allocation.exception.RouteIncompatibleException;
import escuelaing.edu.co.truckcar.load_allocation.exception.RoutingServiceUnavailableException;
import escuelaing.edu.co.truckcar.load_allocation.model.FreightLoad;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Tests unitarios de VehicleRestrictionValidationService usando MockRestServiceServer
 * para simular las respuestas de fleets-drivers y routing-restrictions.
 *
 * Usa el constructor package-private que acepta un RestClient ya construido,
 * evitando así que la SimpleClientHttpRequestFactory reemplace el interceptor del mock.
 */
class VehicleRestrictionValidationServiceTest {

    private MockRestServiceServer mockServer;
    private VehicleRestrictionValidationService service;

    private static final String FLEETS_URL       = "http://localhost:8081";
    private static final String RESTRICTIONS_URL = "http://localhost:8084";

    @BeforeEach
    void setUp() {
        // Usamos RestTemplate como base porque MockRestServiceServer lo soporta directamente.
        // RestClient.create(restTemplate) comparte el mismo HttpMessageConverters y el mock intercepta.
        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        RestClient restClient = RestClient.create(restTemplate);
        service = new VehicleRestrictionValidationService(restClient, FLEETS_URL, RESTRICTIONS_URL);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario 1: vehículo compatible → sin excepción
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void whenRouteIsPassable_thenNoExceptionIsThrown() {
        // fleets-drivers responde con datos del vehículo
        String vehicleJson = """
                {"driverId":"D1","vehiclePlate":"ABC-123",
                 "maxHeightMeters":4.1,"tareWeightTons":8.0,
                 "grossWeightTons":28.0,"maxPayloadTons":20.0}
                """;
        mockServer.expect(requestTo(FLEETS_URL + "/api/v1/fleets-drivers/drivers/D1"))
                .andRespond(withSuccess(vehicleJson, MediaType.APPLICATION_JSON));

        // routing-restrictions dice que sí pasa
        String compatJson = """
                {"vehiclePlate":"ABC-123","roadCorridor":"Bogotá-Medellín",
                 "isPassable":true,"blockingReasons":[],"warnings":[]}
                """;
        mockServer.expect(requestTo(RESTRICTIONS_URL + "/api/v1/restrictions/validate-compatibility"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(compatJson, MediaType.APPLICATION_JSON));

        FreightLoad load = buildLoad("D1", "ABC-123", "Bogotá", "Medellín", 15.0);

        // No debe lanzar nada
        service.validateCompatibility(load, "D1");
        mockServer.verify();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario 2: vehículo incompatible → RouteIncompatibleException
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void whenRouteIsNotPassable_thenRouteIncompatibleExceptionContainsReasons() {
        String vehicleJson = """
                {"driverId":"D2","vehiclePlate":"XYZ-999",
                 "maxHeightMeters":4.5,"tareWeightTons":12.0,
                 "grossWeightTons":40.0,"maxPayloadTons":28.0}
                """;
        mockServer.expect(requestTo(FLEETS_URL + "/api/v1/fleets-drivers/drivers/D2"))
                .andRespond(withSuccess(vehicleJson, MediaType.APPLICATION_JSON));

        String incompatJson = """
                {"vehiclePlate":"XYZ-999","roadCorridor":"Bogotá-Manizales",
                 "isPassable":false,
                 "blockingReasons":["GALIBO_ALTURA: vehiculo 4.50 m supera galibo maximo 4.20 m",
                                    "PESO_BRUTO: 40.00 Tn supera el limite 32.00 Tn"],
                 "warnings":[]}
                """;
        mockServer.expect(requestTo(RESTRICTIONS_URL + "/api/v1/restrictions/validate-compatibility"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(incompatJson, MediaType.APPLICATION_JSON));

        FreightLoad load = buildLoad("D2", "XYZ-999", "Bogotá", "Manizales", 28.0);

        assertThatThrownBy(() -> service.validateCompatibility(load, "D2"))
                .isInstanceOf(RouteIncompatibleException.class)
                .satisfies(ex -> {
                    RouteIncompatibleException rie = (RouteIncompatibleException) ex;
                    assertThat(rie.getBlockingReasons()).hasSize(2);
                    assertThat(rie.getBlockingReasons().get(0)).contains("GALIBO_ALTURA");
                    assertThat(rie.getBlockingReasons().get(1)).contains("PESO_BRUTO");
                });
        mockServer.verify();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario 3: routing-restrictions no responde → RoutingServiceUnavailableException
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void whenRoutingServiceIsDown_thenRoutingServiceUnavailableExceptionIsThrown() {
        String vehicleJson = """
                {"driverId":"D3","vehiclePlate":"LMN-456",
                 "maxHeightMeters":4.0,"tareWeightTons":7.0,
                 "grossWeightTons":25.0,"maxPayloadTons":18.0}
                """;
        mockServer.expect(requestTo(FLEETS_URL + "/api/v1/fleets-drivers/drivers/D3"))
                .andRespond(withSuccess(vehicleJson, MediaType.APPLICATION_JSON));

        // Simula error de servidor / servicio no disponible
        mockServer.expect(requestTo(RESTRICTIONS_URL + "/api/v1/restrictions/validate-compatibility"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        FreightLoad load = buildLoad("D3", "LMN-456", "Cali", "Pasto", 18.0);

        assertThatThrownBy(() -> service.validateCompatibility(load, "D3"))
                .isInstanceOf(RoutingServiceUnavailableException.class)
                .hasMessageContaining("restricciones viales");
        mockServer.verify();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario 4: fleets-drivers no responde → usa valores por defecto y aun valida
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void whenFleetsDriversIsDown_thenFallbackValuesAreUsedAndValidationStillRuns() {
        // fleets-drivers falla
        mockServer.expect(requestTo(FLEETS_URL + "/api/v1/fleets-drivers/drivers/D4"))
                .andRespond(withServerError());

        // routing-restrictions confirma que pasa (con los valores por defecto)
        String compatJson = """
                {"vehiclePlate":"DEF-789","roadCorridor":"Bogotá-Tunja",
                 "isPassable":true,"blockingReasons":[],"warnings":["Dato estimado: peso no confirmado"]}
                """;
        mockServer.expect(requestTo(RESTRICTIONS_URL + "/api/v1/restrictions/validate-compatibility"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(compatJson, MediaType.APPLICATION_JSON));

        FreightLoad load = buildLoad("D4", "DEF-789", "Bogotá", "Tunja", 10.0);

        // No debe lanzar excepción: el error de fleets-drivers se absorbe con un warn y la validación continúa
        service.validateCompatibility(load, "D4");
        mockServer.verify();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Escenario 5: resolveCorridor con diversas combinaciones
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void resolveCorridor_withNullCities_returnsFallback() {
        FreightLoad load = FreightLoad.builder().originCity(null).destinationCity(null).build();
        assertThat(VehicleRestrictionValidationService.resolveCorridor(load)).isEqualTo("CORREDOR-NACIONAL");
    }

    @Test
    void resolveCorridor_withOnlyOrigin_returnsOrigin() {
        FreightLoad load = FreightLoad.builder().originCity("Bogotá").destinationCity(null).build();
        assertThat(VehicleRestrictionValidationService.resolveCorridor(load)).isEqualTo("Bogotá");
    }

    @Test
    void resolveCorridor_withBothCities_returnsDashSeparated() {
        FreightLoad load = FreightLoad.builder().originCity("Cali").destinationCity("Bucaramanga").build();
        assertThat(VehicleRestrictionValidationService.resolveCorridor(load)).isEqualTo("Cali-Bucaramanga");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private FreightLoad buildLoad(String driverId, String plate, String origin, String dest, double weightTons) {
        return FreightLoad.builder()
                .id(1L)
                .assignedDriverId(driverId)
                .assignedVehiclePlate(plate)
                .originCity(origin)
                .destinationCity(dest)
                .weightTons(weightTons)
                .build();
    }
}
