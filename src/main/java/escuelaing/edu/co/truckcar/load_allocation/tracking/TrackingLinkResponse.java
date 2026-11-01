package escuelaing.edu.co.truckcar.load_allocation.tracking;

import java.time.Instant;

/** Enlace generado: url lista para compartir (la PWA con ?track=<token>) y su vencimiento. */
public record TrackingLinkResponse(String token, String url, Instant expiresAt) {
}
