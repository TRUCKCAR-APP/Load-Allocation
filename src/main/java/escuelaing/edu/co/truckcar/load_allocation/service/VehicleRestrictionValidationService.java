package escuelaing.edu.co.truckcar.load_allocation.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import escuelaing.edu.co.truckcar.load_allocation.exception.RouteIncompatibleException;
import escuelaing.edu.co.truckcar.load_allocation.exception.RoutingServiceUnavailableException;
import escuelaing.edu.co.truckcar.load_allocation.model.FreightLoad;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Valida la compatibilidad técnica y vial entre el camión asignado y las restricciones de ruta registradas
 * en ms-routing-restrictions (gálibo, peso bruto, peso por eje y cierres).
 */
@Slf4j
@Service
public class VehicleRestrictionValidationService {

    private final RestClient restClient;
    private final String fleetsDriversUrl;
    private final String routingRestrictionsUrl;

    public VehicleRestrictionValidationService(
            RestClient.Builder builder,
            @Value("${truckcar.services.fleets-drivers-url:${truckcar.tracking.fleets-drivers-url:http://localhost:8081}}") String fleetsDriversUrl,
            @Value("${truckcar.services.routing-restrictions-url:http://localhost:8084}") String routingRestrictionsUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.restClient = builder.requestFactory(factory).build();
        this.fleetsDriversUrl = fleetsDriversUrl;
        this.routingRestrictionsUrl = routingRestrictionsUrl;
    }

    /**
     * Constructor para tests: recibe un RestClient ya configurado (p.ej. con MockRestServiceServer)
     * sin aplicar timeout factory, para que el mock pueda interceptar las peticiones.
     */
    VehicleRestrictionValidationService(RestClient restClient, String fleetsDriversUrl, String routingRestrictionsUrl) {
        this.restClient = restClient;
        this.fleetsDriversUrl = fleetsDriversUrl;
        this.routingRestrictionsUrl = routingRestrictionsUrl;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VehicleState {
        private String driverId;
        private String vehiclePlate;
        private Double maxHeightMeters;
        private Double tareWeightTons;
        private Double grossWeightTons;
        private Double maxPayloadTons;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CompatibilityResponse {
        private String vehiclePlate;
        private String roadCorridor;
        private Boolean isPassable;
        private List<String> blockingReasons;
        private List<String> warnings;
    }

    /**
     * Valida que el vehículo y la carga no violen ninguna restricción vial activa en el corredor.
     * Si no es transitable, lanza {@link RouteIncompatibleException} con las razones bloqueantes.
     * Si el servicio no responde, lanza {@link RoutingServiceUnavailableException} para evitar iniciar a ciegas.
     */
    public void validateCompatibility(FreightLoad load, String driverId) {
        VehicleState vehicle = fetchVehicleState(driverId, load.getAssignedVehiclePlate());

        String plate = (vehicle != null && vehicle.getVehiclePlate() != null && !vehicle.getVehiclePlate().isBlank())
                ? vehicle.getVehiclePlate()
                : (load.getAssignedVehiclePlate() != null ? load.getAssignedVehiclePlate() : "SIN-PLACA");

        double heightMeters = (vehicle != null && vehicle.getMaxHeightMeters() != null)
                ? vehicle.getMaxHeightMeters()
                : 4.10;

        double tare = (vehicle != null && vehicle.getTareWeightTons() != null)
                ? vehicle.getTareWeightTons()
                : 0.0;
        double cargo = load.getWeightTons() != null ? load.getWeightTons() : 0.0;
        double grossWeight = tare + cargo;
        if (grossWeight == 0.0 && vehicle != null && vehicle.getGrossWeightTons() != null) {
            grossWeight = vehicle.getGrossWeightTons();
        }
        if (grossWeight == 0.0) {
            grossWeight = cargo > 0 ? cargo : 10.0;
        }

        Double axleWeight = grossWeight > 0 ? (grossWeight / 3.0) : null;

        String corridor = resolveCorridor(load);

        checkRouteCompatibility(plate, corridor, grossWeight, heightMeters, axleWeight, load.getId());
    }

    private VehicleState fetchVehicleState(String driverId, String fallbackPlate) {
        if (driverId != null && !driverId.isBlank()) {
            try {
                return restClient.get()
                        .uri(fleetsDriversUrl + "/api/v1/fleets-drivers/drivers/{driverId}", driverId)
                        .retrieve()
                        .body(VehicleState.class);
            } catch (Exception e) {
                log.warn("No se pudo consultar estado del conductor {} en fleets-drivers ({}); se usará información disponible",
                        driverId, e.getMessage());
            }
        }
        return null;
    }

    private void checkRouteCompatibility(String plate, String corridor, double grossWeight, double height,
                                         Double axleWeight, Long loadId) {
        CompatibilityResponse response;
        try {
            Map<String, Object> reqBody = new HashMap<>();
            reqBody.put("vehiclePlate", plate);
            reqBody.put("roadCorridor", corridor);
            reqBody.put("grossWeightTons", grossWeight);
            reqBody.put("heightMeters", height);
            if (axleWeight != null) {
                reqBody.put("axleWeightTons", axleWeight);
            }

            response = restClient.post()
                    .uri(routingRestrictionsUrl + "/api/v1/restrictions/validate-compatibility")
                    .body(reqBody)
                    .retrieve()
                    .body(CompatibilityResponse.class);
        } catch (RestClientException e) {
            log.error("Routing-Restrictions no disponible en {} al validar carga #{}: {}", routingRestrictionsUrl, loadId, e.getMessage());
            throw new RoutingServiceUnavailableException(
                    "El servicio de restricciones viales no respondió al validar la carga #" + loadId
                            + "; por seguridad no se permite continuar la operación. Intente nuevamente.", e);
        } catch (Exception e) {
            log.error("Error al conectar con Routing-Restrictions para la carga #{}: {}", loadId, e.getMessage());
            throw new RoutingServiceUnavailableException(
                    "Fallo al validar restricciones viales para la carga #" + loadId + ": " + e.getMessage(), e);
        }

        if (response != null && Boolean.FALSE.equals(response.getIsPassable())) {
            List<String> reasons = response.getBlockingReasons() != null ? response.getBlockingReasons() : List.of();
            String reasonText = reasons.isEmpty() ? "Ruta intransitable para este vehículo" : String.join("; ", reasons);
            log.warn("Carga #{} incompatible con restricciones viales en corredor {} (placa {}): {}",
                    loadId, corridor, plate, reasonText);
            throw new RouteIncompatibleException(
                    "No se puede continuar con la carga #" + loadId + " debido a restricciones viales en corredor '"
                            + corridor + "': " + reasonText, reasons);
        }
    }

    public static String resolveCorridor(FreightLoad load) {
        String origin = load.getOriginCity() != null ? load.getOriginCity().trim() : "";
        String dest = load.getDestinationCity() != null ? load.getDestinationCity().trim() : "";
        if (!origin.isEmpty() && !dest.isEmpty()) {
            return origin + "-" + dest;
        } else if (!origin.isEmpty()) {
            return origin;
        } else if (!dest.isEmpty()) {
            return dest;
        }
        return "CORREDOR-NACIONAL";
    }
}

