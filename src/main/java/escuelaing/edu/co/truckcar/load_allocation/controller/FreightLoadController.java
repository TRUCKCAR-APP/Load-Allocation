package escuelaing.edu.co.truckcar.load_allocation.controller;

import escuelaing.edu.co.truckcar.load_allocation.dto.request.AssignDriverRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.CancelLoadRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.CreateFreightLoadRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.response.DriverCommitmentResponse;
import escuelaing.edu.co.truckcar.load_allocation.dto.response.FreightLoadResponse;
import escuelaing.edu.co.truckcar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckcar.load_allocation.model.CorrelationIds;
import escuelaing.edu.co.truckcar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.TruckTypeRequired;
import escuelaing.edu.co.truckcar.load_allocation.service.IFreightLoadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/loads")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class FreightLoadController {

    private static final String AUTH_USER_HEADER = "X-Auth-User";
    private static final String AUTH_ROLE_HEADER = "X-Auth-Role";
    private static final Set<String> DISPATCH_ROLES = Set.of("COORDINADOR", "ADMIN", "SYSTEM");

    private final IFreightLoadService service;

    @PostMapping
    public ResponseEntity<FreightLoadResponse> publishLoad(
            @Valid @RequestBody CreateFreightLoadRequest request,
            @RequestHeader(value = CorrelationIds.HEADER, required = false) String correlationHeader,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String authenticatedRole) {
        UUID correlationId = CorrelationIds.fromHeader(correlationHeader);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(CorrelationIds.HEADER, correlationId.toString())
                .body(service.publishLoad(request, correlationId, authenticatedUserId, authenticatedRole));
    }

    /** Envíos del generador autenticado: cargas cuyo generatorCompanyId es su id de usuario. */
    @GetMapping("/published")
    public ResponseEntity<List<FreightLoadResponse>> getPublishedLoads(
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String authenticatedRole) {
        return ResponseEntity.ok(service.getPublishedLoads(authenticatedUserId, authenticatedRole));
    }

    @PatchMapping("/{loadId}/assign")
    public ResponseEntity<FreightLoadResponse> assignDriver(
            @PathVariable Long loadId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String authenticatedRole,
            @Valid @RequestBody AssignDriverRequest request) {
        requireDispatcher(authenticatedRole);
        return ResponseEntity.ok(service.assignDriver(loadId, request));
    }

    @PatchMapping("/{loadId}/assignment/notify")
    public ResponseEntity<FreightLoadResponse> notifyAssignment(
            @PathVariable Long loadId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String authenticatedRole) {
        requireDispatcher(authenticatedRole);
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

    // La entrega (PATCH /{loadId}/trip/deliver) y la prueba de entrega están en ProofOfDeliveryController.

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

    /**
     * Conductores ocupados (carga aceptada, en ruta o con propuesta vigente); lo usa el motor de asignación. Revela
     * qué conductores están libres: solo la torre de control y los servicios internos (KAN-43).
     */
    @GetMapping("/driver-commitments")
    public ResponseEntity<List<DriverCommitmentResponse>> getDriverCommitments(
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String authenticatedRole) {
        requireDispatcherOrInternal(authenticatedUserId, authenticatedRole, "la ocupación de los conductores");
        return ResponseEntity.ok(service.getDriverCommitments(authenticatedRole));
    }

    @PatchMapping("/{loadId}/assignment/timeout")
    public ResponseEntity<FreightLoadResponse> timeoutAssignment(
            @PathVariable Long loadId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String authenticatedRole) {
        requireDispatcher(authenticatedRole);
        return ResponseEntity.ok(service.timeoutAssignment(loadId));
    }

    @GetMapping("/{loadId}")
    public ResponseEntity<FreightLoadResponse> getLoadById(
            @PathVariable Long loadId,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String authenticatedRole) {
        return ResponseEntity.ok(service.getLoadById(loadId, authenticatedUserId, authenticatedRole));
    }

    /**
     * Bolsa de cargas sin conductor de todas las empresas (con su presupuesto): la usan la torre de control y el motor
     * de asignación. La app del conductor no la consulta; sus cargas llegan por /mine.
     */
    @GetMapping("/available")
    public ResponseEntity<List<FreightLoadResponse>> getAvailableLoads(
            @RequestParam(required = false) TruckTypeRequired truckType,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String authenticatedRole) {
        requireDispatcherOrInternal(authenticatedUserId, authenticatedRole, "la bolsa de cargas disponibles");
        return ResponseEntity.ok(service.getAvailableLoads(truckType, authenticatedRole));
    }

    @GetMapping("/company/{companyId}")
    public ResponseEntity<List<FreightLoadResponse>> getLoadsByCompany(
            @PathVariable String companyId,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String authenticatedRole) {
        return ResponseEntity.ok(service.getLoadsByCompany(companyId, authenticatedUserId, authenticatedRole));
    }

    /**
     * Operaciones del ciclo de asignación (proponer, notificar, vencer): solo la torre de control (COORDINADOR, ADMIN)
     * y el motor de asignación, que llama por la red interna con X-Auth-Role: SYSTEM. El gateway fija X-Auth-Role desde
     * el JWT y descarta el que envíe el cliente, y ningún usuario tiene rol SYSTEM: un CONDUCTOR no puede autoasignarse.
     */
    private static void requireDispatcher(String authenticatedRole) {
        if (authenticatedRole == null || authenticatedRole.isBlank()) {
            throw new AssignmentAccessDeniedException("Se requiere un rol autenticado para operar la asignación", true);
        }
        if (!DISPATCH_ROLES.contains(authenticatedRole)) {
            throw new AssignmentAccessDeniedException("El rol " + authenticatedRole
                    + " no puede operar el ciclo de asignación (solo COORDINADOR, ADMIN o el motor de asignación)", false);
        }
    }

    /**
     * Consultas de toda la operación: torre de control (COORDINADOR, ADMIN), el motor con X-Auth-Role: SYSTEM o una
     * llamada interna sin identidad. Por el gateway nunca llega una petición sin X-Auth-User en estas rutas (exigen
     * JWT y el gateway reescribe X-Auth-*), así que "sin identidad" solo puede venir de la red interna.
     */
    private static void requireDispatcherOrInternal(String authenticatedUserId, String authenticatedRole, String resource) {
        boolean anonymous = (authenticatedUserId == null || authenticatedUserId.isBlank())
                && (authenticatedRole == null || authenticatedRole.isBlank());
        // Set.of(...).contains(null) lanza NullPointerException: el rol vacío se comprueba antes.
        if (anonymous || (authenticatedRole != null && DISPATCH_ROLES.contains(authenticatedRole))) {
            return;
        }
        throw new AssignmentAccessDeniedException("El rol " + authenticatedRole + " no tiene acceso a " + resource
                + " (solo COORDINADOR, ADMIN o servicios internos)", false);
    }
}
