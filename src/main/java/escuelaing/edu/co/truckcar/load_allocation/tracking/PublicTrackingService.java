package escuelaing.edu.co.truckcar.load_allocation.tracking;

import escuelaing.edu.co.truckcar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckcar.load_allocation.exception.FreightLoadNotFoundException;
import escuelaing.edu.co.truckcar.load_allocation.exception.InvalidLoadOperationException;
import escuelaing.edu.co.truckcar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckcar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckcar.load_allocation.repository.FreightLoadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

/**
 * Enlaces públicos de seguimiento de un envío. Los genera el GENERADOR dueño de la carga o la torre de control y
 * vencen a las 48 h o 6 h después de la entrega (lo que ocurra primero). La vista pública no lleva datos personales:
 * placa enmascarada, sin conductor; la posición en vivo solo mientras la carga va en ruta.
 */
@Service
@RequiredArgsConstructor
public class PublicTrackingService {

    private static final Set<String> CONTROL_TOWER = Set.of("COORDINADOR", "ADMIN");
    private static final Map<LoadStatus, String> LABELS = Map.of(
            LoadStatus.AVAILABLE, "Buscando conductor",
            LoadStatus.ASSIGNED, "Conductor asignado",
            LoadStatus.IN_TRANSIT, "En ruta",
            LoadStatus.DELIVERED, "Entregado",
            LoadStatus.CANCELLED, "Cancelado");

    private final FreightLoadRepository repository;
    private final TrackingTokenService tokens;
    private final FleetPositionClient fleet;
    private final TrackingProperties properties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public TrackingLinkResponse createLink(Long loadId, String userId, String role) {
        if (userId == null || userId.isBlank()) {
            throw new AssignmentAccessDeniedException("Se requiere un usuario autenticado para compartir el seguimiento", true);
        }
        FreightLoad load = repository.findById(loadId)
                .orElseThrow(() -> new FreightLoadNotFoundException("No se encontró la carga con ID: " + loadId));
        boolean owner = "GENERADOR".equals(role) && userId.equals(load.getGeneratorCompanyId());
        if (!owner && (role == null || !CONTROL_TOWER.contains(role))) {
            throw new AssignmentAccessDeniedException("Solo el generador dueño de la carga #" + loadId
                    + " o la torre de control pueden compartir su seguimiento", false);
        }
        if (load.getStatus() == LoadStatus.CANCELLED) {
            throw new InvalidLoadOperationException("La carga #" + loadId + " está cancelada: no hay nada que seguir");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.getMaxTtl());
        Instant deliveryLimit = deliveryLimit(load);
        if (deliveryLimit != null && deliveryLimit.isBefore(expiresAt)) {
            expiresAt = deliveryLimit;
        }
        if (!expiresAt.isAfter(now)) {
            throw new InvalidLoadOperationException("La carga #" + loadId + " se entregó hace más de "
                    + properties.getAfterDelivery().toHours() + " h: el seguimiento ya no está disponible");
        }
        String token = tokens.issue(loadId, now, expiresAt);
        String base = properties.getPublicBaseUrl();
        String url = base + (base.contains("?") ? "&" : "?") + "track=" + token;
        return new TrackingLinkResponse(token, url, expiresAt);
    }

    @Transactional(readOnly = true)
    public PublicTrackingResponse view(String token) {
        Instant now = clock.instant();
        TrackingTokenService.Claims claims = tokens.verify(token, now)
                .orElseThrow(() -> new TrackingLinkUnavailableException("El enlace de seguimiento no es válido o ya venció"));
        FreightLoad load = repository.findById(claims.loadId())
                .orElseThrow(() -> new TrackingLinkUnavailableException("El envío de este enlace ya no existe"));
        if (load.getStatus() == LoadStatus.CANCELLED) {
            throw new TrackingLinkUnavailableException("El envío fue cancelado");
        }
        Instant deliveryLimit = deliveryLimit(load);
        if (deliveryLimit != null && !now.isBefore(deliveryLimit)) {
            throw new TrackingLinkUnavailableException("El envío se entregó y su seguimiento terminó");
        }

        PublicTrackingResponse.Position position = null;
        if (load.getStatus() == LoadStatus.IN_TRANSIT && load.getAssignedDriverId() != null) {
            position = fleet.latest(load.getAssignedDriverId())
                    // Solo si el vehículo lleva esta carga: nunca la posición de otro viaje del mismo conductor.
                    .filter(p -> p.currentLoadId() == null || p.currentLoadId().equals(load.getId()))
                    .map(p -> new PublicTrackingResponse.Position(p.latitude(), p.longitude(), p.speedKmH(), p.heading(), p.updatedAt()))
                    .orElse(null);
        }
        Instant expiresAt = deliveryLimit != null && deliveryLimit.isBefore(claims.expiresAt()) ? deliveryLimit : claims.expiresAt();
        return new PublicTrackingResponse(
                "CARGA-" + load.getId(),
                load.getStatus().name(),
                LABELS.getOrDefault(load.getStatus(), load.getStatus().name()),
                load.getCargoDescription(),
                load.getWeightTons(),
                new PublicTrackingResponse.Place(load.getOriginCity(), load.getOriginDepartment()),
                new PublicTrackingResponse.Place(load.getDestinationCity(), load.getDestinationDepartment()),
                toInstant(load.getPublishedAt()),
                toInstant(load.getAssignedAt()),
                toInstant(load.getTripStartedAt()),
                toInstant(load.getDeliveredAt()),
                maskPlate(load.getAssignedVehiclePlate()),
                position,
                expiresAt);
    }

    private Instant deliveryLimit(FreightLoad load) {
        if (load.getStatus() != LoadStatus.DELIVERED || load.getDeliveredAt() == null) {
            return null;
        }
        return toInstant(load.getDeliveredAt()).plus(properties.getAfterDelivery());
    }

    /** "TDK-410" -> "TDK-•••": suficiente para reconocer el camión al llegar, sin publicar la placa completa. */
    static String maskPlate(String plate) {
        if (plate == null || plate.isBlank()) {
            return null;
        }
        String clean = plate.trim();
        int visible = Math.min(4, Math.max(1, clean.length() - 3));
        return clean.substring(0, visible) + "•".repeat(clean.length() - visible);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }
}
