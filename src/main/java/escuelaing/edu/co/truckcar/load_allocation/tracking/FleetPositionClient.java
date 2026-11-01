package escuelaing.edu.co.truckcar.load_allocation.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * Última posición del vehículo en fleets-drivers (llamada interna, sin identidad de usuario). Si fleets-drivers no
 * responde, el seguimiento público se muestra sin posición en vez de fallar.
 */
@Slf4j
@Component
public class FleetPositionClient {

    private final RestClient restClient;

    public FleetPositionClient(RestClient.Builder builder, TrackingProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        this.restClient = builder.baseUrl(properties.getFleetsDriversUrl()).requestFactory(factory).build();
    }

    public record Position(double latitude, double longitude, Double speedKmH, Double heading, String updatedAt,
                           Long currentLoadId) {
    }

    public Optional<Position> latest(String driverId) {
        try {
            JsonNode state = restClient.get()
                    .uri("/api/v1/fleets-drivers/drivers/{driverId}", driverId)
                    .retrieve()
                    .body(JsonNode.class);
            if (state == null || !state.path("currentLatitude").isNumber() || !state.path("currentLongitude").isNumber()) {
                return Optional.empty();
            }
            return Optional.of(new Position(
                    state.get("currentLatitude").asDouble(),
                    state.get("currentLongitude").asDouble(),
                    state.path("currentSpeedKmH").isNumber() ? state.get("currentSpeedKmH").asDouble() : null,
                    state.path("currentHeading").isNumber() ? state.get("currentHeading").asDouble() : null,
                    state.path("lastUpdate").isMissingNode() || state.get("lastUpdate").isNull() ? null : state.get("lastUpdate").asText(),
                    state.path("currentLoadId").canConvertToLong() ? state.get("currentLoadId").asLong() : null));
        } catch (Exception e) {
            log.warn("Sin posición de fleets-drivers para el seguimiento público del conductor {}: {}", driverId, e.getMessage());
            return Optional.empty();
        }
    }
}
