package escuelaing.edu.co.truckdar.load_allocation.controller;

import escuelaing.edu.co.truckdar.load_allocation.dto.request.AssignDriverRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.request.CancelLoadRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.request.CreateFreightLoadRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.request.DeliveryConfirmationRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.response.DriverCommitmentResponse;
import escuelaing.edu.co.truckdar.load_allocation.dto.response.FreightLoadResponse;
import escuelaing.edu.co.truckdar.load_allocation.dto.response.ProofOfDeliveryResponse;
import escuelaing.edu.co.truckdar.load_allocation.model.CorrelationIds;
import escuelaing.edu.co.truckdar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckdar.load_allocation.model.TruckTypeRequired;
import escuelaing.edu.co.truckdar.load_allocation.service.IFreightLoadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/loads")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class FreightLoadController {

    private static final String AUTH_USER_HEADER = "X-Auth-User";
    private static final String AUTH_ROLE_HEADER = "X-Auth-Role";

    private final IFreightLoadService service;

    @PostMapping
    public ResponseEntity<FreightLoadResponse> publishLoad(
            @Valid @RequestBody CreateFreightLoadRequest request,
            @RequestHeader(value = CorrelationIds.HEADER, required = false) String correlationHeader) {
        UUID correlationId = CorrelationIds.fromHeader(correlationHeader);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(CorrelationIds.HEADER, correlationId.toString())
                .body(service.publishLoad(request, correlationId));
    }

    @PatchMapping("/{loadId}/assign")
    public ResponseEntity<FreightLoadResponse> assignDriver(
            @PathVariable Long loadId,
            @Valid @RequestBody AssignDriverRequest request) {
        return ResponseEntity.ok(service.assignDriver(loadId, request));
    }

    @PatchMapping("/{loadId}/assignment/notify")
    public ResponseEntity<FreightLoadResponse> notifyAssignment(@PathVariable Long loadId) {
        return ResponseEntity.ok(service.notifyAssignment(loadId));
    }

    /** X-Auth-User lo fija el api-gateway con el sujeto del JWT; debe coincidir con el conductor asignado. */
    @PatchMapping("/{loadId}/assignment/accept")
    public ResponseEntity<FreightLoadResponse> acceptAssignment(
            @PathVariable Long loadId,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId) {
        return ResponseEntity.ok(service.acceptAssignment(loadId, authenticatedUserId));
    }

    @PatchMapping("/{loadId}/assignment/reject")
    public ResponseEntity<FreightLoadResponse> rejectAssignment(
            @PathVariable Long loadId,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId) {
        return ResponseEntity.ok(service.rejectAssignment(loadId, authenticatedUserId));
    }

    /** ASSIGNED -> IN_TRANSIT. Solo el conductor asignado. */
    @PatchMapping("/{loadId}/trip/start")
    public ResponseEntity<FreightLoadResponse> startTrip(
            @PathVariable Long loadId,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId) {
        return ResponseEntity.ok(service.startTrip(loadId, authenticatedUserId));
    }

    /** IN_TRANSIT -> DELIVERED con prueba de entrega (POD). Solo el conductor asignado. */
    @PatchMapping("/{loadId}/trip/deliver")
    public ResponseEntity<FreightLoadResponse> deliverLoad(
            @PathVariable Long loadId,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId,
            @Valid @RequestBody DeliveryConfirmationRequest request) {
        return ResponseEntity.ok(service.deliverLoad(loadId, authenticatedUserId, request));
    }

    /** AVAILABLE | ASSIGNED -> CANCELLED. Solo COORDINADOR o ADMIN (rol propagado por el gateway en X-Auth-Role). */
    @PatchMapping("/{loadId}/cancel")
    public ResponseEntity<FreightLoadResponse> cancelLoad(
            @PathVariable Long loadId,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String authenticatedRole,
            @Valid @RequestBody CancelLoadRequest request) {
        return ResponseEntity.ok(service.cancelLoad(loadId, authenticatedUserId, authenticatedRole, request));
    }

    /** Cargas del conductor autenticado: propuestas vigentes, aceptadas y en tránsito. */
    @GetMapping("/mine")
    public ResponseEntity<List<FreightLoadResponse>> getMyLoads(
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId) {
        return ResponseEntity.ok(service.getLoadsForDriver(authenticatedUserId));
    }

    /** Torre de control: cargas de todos los generadores, opcionalmente filtradas (?status=DELIVERED&status=...). */
    @GetMapping
    public ResponseEntity<List<FreightLoadResponse>> listLoads(
            @RequestParam(value = "status", required = false) List<LoadStatus> statuses,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String authenticatedRole) {
        return ResponseEntity.ok(service.listLoads(statuses, authenticatedUserId, authenticatedRole));
    }

    /** POD completo (firma y foto incluidas): conductor de la carga o torre de control. */
    @GetMapping("/{loadId}/proof-of-delivery")
    public ResponseEntity<ProofOfDeliveryResponse> getProofOfDelivery(
            @PathVariable Long loadId,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String authenticatedRole) {
        return ResponseEntity.ok(service.getProofOfDelivery(loadId, authenticatedUserId, authenticatedRole));
    }

    /** Conductores ocupados (carga aceptada, en ruta o con propuesta vigente); lo usa el motor de asignación. */
    @GetMapping("/driver-commitments")
    public ResponseEntity<List<DriverCommitmentResponse>> getDriverCommitments() {
        return ResponseEntity.ok(service.getDriverCommitments());
    }

    @PatchMapping("/{loadId}/assignment/timeout")
    public ResponseEntity<FreightLoadResponse> timeoutAssignment(@PathVariable Long loadId) {
        return ResponseEntity.ok(service.timeoutAssignment(loadId));
    }

    @GetMapping("/{loadId}")
    public ResponseEntity<FreightLoadResponse> getLoadById(@PathVariable Long loadId) {
        return ResponseEntity.ok(service.getLoadById(loadId));
    }

    @GetMapping("/available")
    public ResponseEntity<List<FreightLoadResponse>> getAvailableLoads(
            @RequestParam(required = false) TruckTypeRequired truckType) {
        return ResponseEntity.ok(service.getAvailableLoads(truckType));
    }

    @GetMapping("/company/{companyId}")
    public ResponseEntity<List<FreightLoadResponse>> getLoadsByCompany(@PathVariable String companyId) {
        return ResponseEntity.ok(service.getLoadsByCompany(companyId));
    }
}