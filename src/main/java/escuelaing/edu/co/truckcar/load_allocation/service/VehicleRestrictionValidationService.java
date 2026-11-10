package escuelaing.edu.co.truckcar.load_allocation.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import escuelaing.edu.co.truckcar.load_allocation.exception.RouteIncompatibleException;
import escuelaing.edu.co.truckcar.load_allocation.exception.RoutingServiceUnavailableException;
import escuelaing.edu.co.truckcar.load_allocation.model.FreightLoad;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Valida la compatibilidad técnica y vial entre el camión asignado y las restricciones de ruta registradas
 * en ms-routing-restrictions (gálibo, peso bruto, peso por eje y cierres).
 * <p>
 * Prioriza la validación sobre la geometría real de la ruta obtenida de OSRM ({@code /validate-route}); si
 * OSRM no está disponible o la carga no cuenta con coordenadas geográficas, realiza la validación canónica
 * por nombre de corredor bidireccional ({@code /validate-compatibility}).
 */
@Slf4j
@Service
public class VehicleRestrictionValidationService {

    private static final int MAX_PATH_POINTS = 400;

    private final RestClient restClient;
    private final String fleetsDriversUrl;
    private final String routingRestrictionsUrl;
    private final String osrmUrl;

    @Autowired
    public VehicleRestrictionValidationService(
            RestClient.Builder builder,
            @Value("${truckcar.services.fleets-drivers-url:${truckcar.tracking.fleets-drivers-url:http://localhost:8081}}") String fleetsDriversUrl,
            @Value("${truckcar.services.routing-restrictions-url:http://localhost:8084}") String routingRestrictionsUrl,
            @Value("${truckcar.routing.osrm-url:https://router.project-osrm.org}") String osrmUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.restClient = builder.requestFactory(factory).build();
        this.fleetsDriversUrl = fleetsDriversUrl != null ? fleetsDriversUrl.replaceAll("/+$", "") : "";
        this.routingRestrictionsUrl = routingRestrictionsUrl != null ? routingRestrictionsUrl.replaceAll("/+$", "") : "";
        this.osrmUrl = osrmUrl != null ? osrmUrl.replaceAll("/+$", "") : "";
    }

    /**
     * Constructor para tests: recibe un RestClient ya configurado sin aplicar timeout factory.
     */
    VehicleRestrictionValidationService(RestClient restClient, String fleetsDriversUrl, String routingRestrictionsUrl) {
        this(restClient, fleetsDriversUrl, routingRestrictionsUrl, null);
    }

    VehicleRestrictionValidationService(RestClient restClient, String fleetsDriversUrl, String routingRestrictionsUrl, String osrmUrl) {
        this.restClient = restClient;
        this.fleetsDriversUrl = fleetsDriversUrl != null ? fleetsDriversUrl.replaceAll("/+$", "") : "";
        this.routingRestrictionsUrl = routingRestrictionsUrl != null ? routingRestrictionsUrl.replaceAll("/+$", "") : "";
        this.osrmUrl = osrmUrl != null ? osrmUrl.replaceAll("/+$", "") : "";
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VehicleState {
        private String driverId;
        private String vehiclePlate;
        private String vehicleType;
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
     * Valida que el vehículo y la carga no violen ninguna restricción vial activa en la ruta.
     * Si no es transitable, lanza {@link RouteIncompatibleException} con las razones bloqueantes.
     * Si el servicio no responde, lanza {@link RoutingServiceUnavailableException} para evitar iniciar a ciegas.
     */
    public void validateCompatibility(FreightLoad load, String driverId) {
        VehicleState vehicle = fetchVehicleState(driverId, load.getAssignedVehiclePlate());

        // Principio de seguridad vial: bloqueo cerrado ante falta de datos de flota
        if (vehicle == null) {
            throw new RoutingServiceUnavailableException(
                    "El servicio de flota y conductores (fleets-drivers) no respondió para el conductor "
                            + driverId + "; por seguridad vial se bloquea el inicio del viaje hasta verificar gálibo y peso.", null);
        }

        if (vehicle.getMaxHeightMeters() == null || vehicle.getMaxHeightMeters() <= 0) {
            throw new RouteIncompatibleException(
                    "El vehículo asignado no tiene registrada su altura máxima en fleets-drivers; se requiere ficha técnica completa antes de viajar.",
                    List.of("DATOS_VEHICULO_INCOMPLETOS: falta altura máxima (gálibo) del camión en fleets-drivers"));
        }

        String plate = (vehicle.getVehiclePlate() != null && !vehicle.getVehiclePlate().isBlank())
                ? vehicle.getVehiclePlate()
                : (load.getAssignedVehiclePlate() != null ? load.getAssignedVehiclePlate() : "SIN-PLACA");

        double heightMeters = vehicle.getMaxHeightMeters();

        double tare = (vehicle.getTareWeightTons() != null && vehicle.getTareWeightTons() > 0)
                ? vehicle.getTareWeightTons()
                : 0.0;
        double cargo = load.getWeightTons() != null ? load.getWeightTons() : 0.0;
        double grossWeight = tare + cargo;
        if (grossWeight == 0.0 && vehicle.getGrossWeightTons() != null) {
            grossWeight = vehicle.getGrossWeightTons();
        }
        if (grossWeight == 0.0) {
            grossWeight = cargo > 0 ? cargo : 10.0;
        }

        // Sin pesaje por eje certificado de báscula en fleets-drivers, no se envía peso estimado como dato duro
        // para evitar bloqueos injustificados. Routing-Restrictions emite advertencia preventiva PESO_POR_EJE_SIN_DATO.
        Double axleWeight = null;

        String corridor = resolveCorridor(load);

        checkRouteCompatibility(plate, corridor, grossWeight, heightMeters, axleWeight, load);
    }

    /**
     * Estimación de ejes a partir de la tipología vehicular colombiana (para fines informativos o métricas).
     */
    static int estimateAxles(String requiredTruckType, String vehicleType) {
        String type = (vehicleType != null && !vehicleType.isBlank()) ? vehicleType.toUpperCase(Locale.ROOT)
                : (requiredTruckType != null ? requiredTruckType.toUpperCase(Locale.ROOT) : "");
        if (type.contains("3S3") || type.contains("C3S3")) return 6;
        if (type.contains("3S2") || type.contains("C3S2")) return 5;
        if (type.contains("CUATRO_MANOS") || type.contains("C4")) return 4;
        if (type.contains("DOBLE_TROQUE") || type.contains("C3")) return 3;
        if (type.contains("SENCILLO") || type.contains("C2")) return 2;
        return 3;
    }

    private VehicleState fetchVehicleState(String driverId, String fallbackPlate) {
        if (driverId != null && !driverId.isBlank()) {
            try {
                return restClient.get()
                        .uri(fleetsDriversUrl + "/api/v1/fleets-drivers/drivers/{driverId}", driverId)
                        .retrieve()
                        .body(VehicleState.class);
            } catch (Exception e) {
                log.error("Fallo al consultar fleets-drivers para el conductor {}: {}", driverId, e.getMessage());
                throw new RoutingServiceUnavailableException(
                        "El servicio fleets-drivers no respondió para el conductor " + driverId
                                + "; por seguridad vial se bloquea la operación hasta verificar gálibo y peso.", e);
            }
        }
        return null;
    }

    private void checkRouteCompatibility(String plate, String corridor, double grossWeight, double height,
                                         Double axleWeight, FreightLoad load) {
        Long loadId = load != null ? load.getId() : null;

        // 1. Si la carga tiene coordenadas y OSRM está configurado, intentar validación sobre la geometría real
        if (hasEndpoints(load) && osrmUrl != null && !osrmUrl.isBlank()) {
            try {
                if (validateViaOsrmGeometry(plate, load, height, grossWeight)) {
                    log.info("Carga #{} validada exitosamente sobre geometría OSRM (placa {})", loadId, plate);
                    return;
                }
            } catch (RouteIncompatibleException e) {
                throw e; // Incompatibilidad física confirmada en todas las alternativas OSRM
            } catch (Exception e) {
                log.info("Validación geométrica OSRM no completada ({}); recurriendo a validación canónica de corredor '{}'",
                        e.getMessage(), corridor);
            }
        }

        // 2. Validación canónica por corredor vial (validate-compatibility)
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

    private boolean validateViaOsrmGeometry(String plate, FreightLoad load, double heightMeters, double grossWeightTons) {
        String key = String.format(Locale.ROOT, "%.5f,%.5f;%.5f,%.5f",
                load.getOriginLongitude(), load.getOriginLatitude(),
                load.getDestinationLongitude(), load.getDestinationLatitude());
        JsonNode osrmResponse = restClient.get()
                .uri(osrmUrl + "/route/v1/driving/" + key + "?overview=full&geometries=geojson&alternatives=true")
                .retrieve()
                .body(JsonNode.class);

        JsonNode routes = osrmResponse == null ? null : osrmResponse.get("routes");
        if (routes == null || !routes.isArray() || routes.isEmpty()) {
            return false;
        }

        List<String> lastBlockingReasons = new ArrayList<>();
        boolean anyPassable = false;

        for (JsonNode route : routes) {
            JsonNode coordinates = route.path("geometry").path("coordinates");
            if (!coordinates.isArray() || coordinates.size() < 2) {
                continue;
            }
            List<List<Double>> path = toLatLng(sample(coordinates));
            Map<String, Object> req = Map.of(
                    "vehiclePlate", plate,
                    "heightMeters", heightMeters,
                    "grossWeightTons", grossWeightTons,
                    "path", path
            );

            JsonNode valResult = restClient.post()
                    .uri(routingRestrictionsUrl + "/api/v1/restrictions/validate-route")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode blocking = valResult == null ? null : valResult.get("blocking");
            if (blocking == null || !blocking.isArray() || blocking.isEmpty()) {
                anyPassable = true;
                break;
            } else {
                lastBlockingReasons.clear();
                for (JsonNode hit : blocking) {
                    lastBlockingReasons.add(hit.path("reason").asText(hit.path("kind").asText("Restricción en ruta")));
                }
            }
        }

        if (anyPassable) {
            return true;
        }
        if (!lastBlockingReasons.isEmpty()) {
            throw new RouteIncompatibleException(
                    "La ruta no admite el vehículo (" + plate + ") por gálibo o peso en ninguna de sus alternativas OSRM",
                    lastBlockingReasons);
        }
        return false;
    }

    private static boolean hasEndpoints(FreightLoad load) {
        return load != null
                && load.getOriginLatitude() != null && load.getOriginLongitude() != null
                && load.getDestinationLatitude() != null && load.getDestinationLongitude() != null;
    }

    private static List<double[]> sample(JsonNode coordinates) {
        int n = coordinates.size();
        int count = Math.min(n, MAX_PATH_POINTS);
        List<double[]> path = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            JsonNode point = coordinates.get(count == n ? i : (int) Math.round(i * (n - 1) / (double) (count - 1)));
            path.add(new double[] {point.get(1).asDouble(), point.get(0).asDouble()});
        }
        return path;
    }

    private static List<List<Double>> toLatLng(List<double[]> path) {
        return path.stream().map(p -> List.of(p[0], p[1])).toList();
    }

    public static String resolveCorridor(FreightLoad load) {
        String origin = normalizeCity(load.getOriginCity());
        String dest = normalizeCity(load.getDestinationCity());
        if (!origin.isEmpty() && !dest.isEmpty()) {
            return origin + "-" + dest;
        } else if (!origin.isEmpty()) {
            return origin;
        } else if (!dest.isEmpty()) {
            return dest;
        }
        return "CORREDOR-NACIONAL";
    }

    public static String normalizeCity(String city) {
        if (city == null || city.isBlank()) {
            return "";
        }
        String withoutAccents = Normalizer.normalize(city, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return withoutAccents.trim()
                .replaceAll("(?i)\\s*d\\.c\\.", "")
                .replaceAll("(?i)\\s*distrito capital", "")
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }
}
