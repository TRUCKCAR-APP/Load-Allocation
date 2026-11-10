package escuelaing.edu.co.truckcar.load_allocation.controller;

import escuelaing.edu.co.truckcar.load_allocation.dto.request.DeliveryConfirmationRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.PodUploadUrlRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.response.FreightLoadResponse;
import escuelaing.edu.co.truckcar.load_allocation.dto.response.PodUploadUrlResponse;
import escuelaing.edu.co.truckcar.load_allocation.dto.response.ProofOfDeliveryResponse;
import escuelaing.edu.co.truckcar.load_allocation.service.IFreightLoadService;
import escuelaing.edu.co.truckcar.load_allocation.service.ProofOfDeliveryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Prueba de entrega (POD): URLs prefirmadas de subida, registro de la entrega y consulta con URLs de descarga. */
@RestController
@RequestMapping("/api/v1/loads")
@RequiredArgsConstructor
public class ProofOfDeliveryController {

    private static final String AUTH_USER_HEADER = "X-Auth-User";
    private static final String AUTH_ROLE_HEADER = "X-Auth-Role";

    private final IFreightLoadService service;
    private final ProofOfDeliveryService proofOfDeliveryService;

    /** Solo el conductor asignado, con la carga ASSIGNED o IN_TRANSIT. */
    @PostMapping("/{loadId}/proof-of-delivery/upload-urls")
    public ResponseEntity<PodUploadUrlResponse> issueUploadUrls(
            @PathVariable Long loadId,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId,
            @Valid @RequestBody PodUploadUrlRequest request) {
        return ResponseEntity.ok(proofOfDeliveryService.issueUploadUrls(loadId, authenticatedUserId, request));
    }

    /** IN_TRANSIT -> DELIVERED. Las evidencias referenciadas deben estar subidas y coincidir con su SHA-256. */
    @PatchMapping("/{loadId}/trip/deliver")
    public ResponseEntity<FreightLoadResponse> deliverLoad(
            @PathVariable Long loadId,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId,
            @Valid @RequestBody DeliveryConfirmationRequest request) {
        proofOfDeliveryService.verifyEvidence(loadId, authenticatedUserId, request);
        return ResponseEntity.ok(service.deliverLoad(loadId, authenticatedUserId, request));
    }

    /** POD completo con URLs de descarga temporales: conductor de la carga, su generador o la torre de control. */
    @GetMapping("/{loadId}/proof-of-delivery")
    public ResponseEntity<ProofOfDeliveryResponse> getProofOfDelivery(
            @PathVariable Long loadId,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String authenticatedRole) {
        return ResponseEntity.ok(proofOfDeliveryService.withDownloadUrls(
                service.getProofOfDelivery(loadId, authenticatedUserId, authenticatedRole)));
    }
}
