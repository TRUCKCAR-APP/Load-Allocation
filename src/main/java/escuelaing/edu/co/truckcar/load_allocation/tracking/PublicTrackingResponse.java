package escuelaing.edu.co.truckcar.load_allocation.tracking;

import java.time.Instant;

/** Vista pública de un envío: sin datos personales del conductor ni del generador. */
public record PublicTrackingResponse(
        String reference,
        String status,
        String statusLabel,
        String cargoDescription,
        Double weightTons,
        Place origin,
        Place destination,
        Instant publishedAt,
        Instant assignedAt,
        Instant tripStartedAt,
        Instant deliveredAt,
        String vehiclePlate,
        Position position,
        Instant expiresAt) {

    public record Place(String city, String department) {
    }

    public record Position(double latitude, double longitude, Double speedKmH, Double heading, String updatedAt) {
    }
}
