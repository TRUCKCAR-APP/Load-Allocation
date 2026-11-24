package escuelaing.edu.co.truckcar.load_allocation.company;

import escuelaing.edu.co.truckcar.load_allocation.dto.request.CreateFreightLoadRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.response.FreightLoadResponse;
import escuelaing.edu.co.truckcar.load_allocation.dto.response.ProofOfDeliveryResponse;
import escuelaing.edu.co.truckcar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckcar.load_allocation.model.CorrelationIds;
import escuelaing.edu.co.truckcar.load_allocation.service.FreightLoadService;
import escuelaing.edu.co.truckcar.load_allocation.service.ProofOfDeliveryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Cargas de la transportadora: las que llevan sus conductores (afiliación ACTIVA) y las propias que publica para su
 * flota. Solo TRANSPORTADORA; la empresa se confirma en User-Identity con el token del usuario, nunca se toma del
 * cuerpo ni de la URL.
 */
@RestController
@RequestMapping("/api/v1/loads/fleet")
@RequiredArgsConstructor
public class CompanyFleetLoadsController {

    private static final String AUTH_USER_HEADER = "X-Auth-User";
    private static final String AUTH_ROLE_HEADER = "X-Auth-Role";

    private final FreightLoadService loads;
    private final ProofOfDeliveryService proofOfDelivery;
    private final UserIdentityCompanyClient userIdentity;

    @GetMapping
    public List<FreightLoadResponse> fleetLoads(
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String userId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String role,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        requireCompany(userId, role);
        return loads.companyFleetLoads(userIdentity.myCompany(authorization).id());
    }

    /** Carga propia: solo se ofrece a los conductores de la empresa. */
    @PostMapping
    public ResponseEntity<FreightLoadResponse> publishOwnLoad(
            @Valid @RequestBody CreateFreightLoadRequest request,
            @RequestHeader(value = CorrelationIds.HEADER, required = false) String correlationHeader,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String userId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String role,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        requireCompany(userId, role);
        UserIdentityCompanyClient.Company company = userIdentity.myCompany(authorization);
        UUID correlationId = CorrelationIds.fromHeader(correlationHeader);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(CorrelationIds.HEADER, correlationId.toString())
                .body(loads.publishOwnLoad(request, correlationId, userId, company.id(), company.name()));
    }

    /** Prueba de entrega de una carga de su flota (propia o llevada por uno de sus conductores). */
    @GetMapping("/{loadId}/proof-of-delivery")
    public ProofOfDeliveryResponse proofOfDelivery(
            @PathVariable Long loadId,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String userId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String role,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        requireCompany(userId, role);
        String companyId = userIdentity.myCompany(authorization).id();
        return proofOfDelivery.withDownloadUrls(loads.companyProofOfDelivery(loadId, companyId));
    }

    private static void requireCompany(String userId, String role) {
        if (userId == null || userId.isBlank()) {
            throw new AssignmentAccessDeniedException("Se requiere un usuario autenticado", true);
        }
        if (!"TRANSPORTADORA".equals(role)) {
            throw new AssignmentAccessDeniedException("Solo una cuenta de transportadora tiene cargas de su flota", false);
        }
    }
}
