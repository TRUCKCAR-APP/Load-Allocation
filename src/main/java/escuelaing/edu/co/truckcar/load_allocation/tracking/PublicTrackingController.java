package escuelaing.edu.co.truckcar.load_allocation.tracking;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seguimiento público de envíos.
 *  - POST /{loadId}/public-tracking-link: GENERADOR dueño, COORDINADOR o ADMIN.
 *  - GET /public-tracking/{token}: anónimo (el api-gateway no exige JWT en esta ruta y descarta los X-Auth-*).
 */
@RestController
@RequestMapping("/api/v1/loads")
@RequiredArgsConstructor
public class PublicTrackingController {

    private final PublicTrackingService service;

    @PostMapping("/{loadId}/public-tracking-link")
    public ResponseEntity<TrackingLinkResponse> createLink(
            @PathVariable Long loadId,
            @RequestHeader(value = "X-Auth-User", required = false) String userId,
            @RequestHeader(value = "X-Auth-Role", required = false) String role) {
        return new ResponseEntity<>(service.createLink(loadId, userId, role), HttpStatus.CREATED);
    }

    /** Sin caché ni Referer: el token va en la URL y no debe quedar en cachés intermedias ni filtrarse a terceros. */
    @GetMapping("/public-tracking/{token}")
    public ResponseEntity<PublicTrackingResponse> view(@PathVariable String token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex")
                .body(service.view(token));
    }
}
