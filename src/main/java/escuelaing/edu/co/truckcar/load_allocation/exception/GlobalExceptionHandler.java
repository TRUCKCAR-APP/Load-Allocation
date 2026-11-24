package escuelaing.edu.co.truckcar.load_allocation.exception;

import escuelaing.edu.co.truckcar.load_allocation.tracking.TrackingLinkUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import software.amazon.awssdk.core.exception.SdkException;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;

/**
 * Errores en formato RFC 7807 (ProblemDetail): type, title, status, detail e instance (la ruta de la petición).
 * <p>
 * Para no romper a los clientes actuales se conservan como propiedades adicionales los campos de antes: message (el
 * texto que muestra la PWA y que AI-fleet lee de los 409), error (= title), details (errores por campo en un 400) y
 * timestamp. Content-Type: application/problem+json para quien lo pida en Accept y application/json para el resto
 * (la PWA envía Accept: application/json y decide por ese tipo si interpreta el cuerpo como JSON).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    static final String TYPE_BASE = "https://truckcar.co/errors/";

    @ExceptionHandler(FreightLoadNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(FreightLoadNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Carga no encontrada", ex.getMessage());
    }

    /** Enlace público inválido, vencido o de un envío cancelado/entregado: 410 sin detalles de la carga. */
    @ExceptionHandler(TrackingLinkUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleTrackingUnavailable(TrackingLinkUnavailableException ex) {
        return problem(HttpStatus.GONE, "tracking-unavailable", "Seguimiento no disponible", ex.getMessage());
    }

    @ExceptionHandler(InvalidLoadOperationException.class)
    public ResponseEntity<ProblemDetail> handleInvalidOperation(InvalidLoadOperationException ex) {
        return problem(HttpStatus.CONFLICT, "invalid-load-operation", "Operación inválida sobre la carga", ex.getMessage());
    }

    @ExceptionHandler(ProofOfDeliveryEvidenceException.class)
    public ResponseEntity<ProblemDetail> handleEvidence(ProofOfDeliveryEvidenceException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-delivery-evidence", "Evidencia de entrega inválida",
                ex.getMessage());
    }

    @ExceptionHandler(escuelaing.edu.co.truckcar.load_allocation.company.CompanyLookupUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleCompanyLookup(
            escuelaing.edu.co.truckcar.load_allocation.company.CompanyLookupUnavailableException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable", "Servicio no disponible", ex.getMessage());
    }

    @ExceptionHandler(AssignmentAccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AssignmentAccessDeniedException ex) {
        return ex.isUnauthenticated()
                ? problem(HttpStatus.UNAUTHORIZED, "unauthenticated", "Usuario no autenticado", ex.getMessage())
                : problem(HttpStatus.FORBIDDEN, "forbidden", "Operación no permitida", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new TreeMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        // message como en el resto de errores: el cliente siempre tiene un texto que mostrar.
        String detail = fieldErrors.isEmpty() ? "La solicitud tiene datos inválidos" : String.join("; ", fieldErrors.values());
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.BAD_REQUEST, "validation-error",
                "Validación de datos fallida", detail);
        response.getBody().setProperty("details", fieldErrors);
        // La misma información como lista (el formato habitual de RFC 7807); details se conserva para la PWA.
        response.getBody().setProperty("errors", fieldErrors.entrySet().stream()
                .map(e -> Map.of("field", e.getKey(), "message", e.getValue())).toList());
        return response;
    }

    /** JSON mal formado, UTF-8 inválido o un valor que no corresponde al tipo (enum desconocido): error del cliente. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Cuerpo ilegible: {}", ex.getMessage());
        // Sin el detalle del parser en la respuesta: puede incluir fragmentos del cuerpo y nombres de clases internas.
        return problem(HttpStatus.BAD_REQUEST, "unreadable-body", "Cuerpo de la solicitud inválido",
                "El cuerpo debe ser JSON válido en UTF-8 con los campos y valores esperados");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-parameter", "Parámetro inválido",
                ex.getName() + ": valor con un tipo incorrecto");
    }

    /**
     * Dos operaciones simultáneas sobre la misma carga (p. ej. el conductor acepta justo cuando vence la propuesta):
     * gana la primera y la otra recibe 409 para que el cliente vuelva a consultar, en lugar de un 500.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleConcurrentChange(ObjectOptimisticLockingFailureException ex) {
        log.info("Cambio concurrente sobre una carga: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, "concurrent-change", "Operación inválida sobre la carga",
                "La carga cambió mientras se procesaba la solicitud; consulte su estado e intente de nuevo");
    }

    /** Un dato que la base rechaza (duplicado, demasiado largo...) es un conflicto con la solicitud, no un fallo interno. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Datos rechazados por la base: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "data-rejected", "Datos inválidos",
                "Los datos enviados no se pudieron guardar; revise la longitud y el formato de los campos");
    }

    /** Almacenamiento de evidencias (S3) caído o sin permisos: 503 para que la PWA reintente la entrega. */
    @ExceptionHandler(SdkException.class)
    public ResponseEntity<ProblemDetail> handleStorageUnavailable(SdkException ex) {
        log.error("Almacenamiento de evidencias no disponible: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "storage-unavailable", "Servicio no disponible",
                "El almacenamiento de evidencias no responde; intente de nuevo en unos minutos");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneral(Exception ex) {
        // Excepciones de Spring MVC con su propio estado (405, 415, parámetro faltante...): este handler genérico las
        // capturaría antes que el resolver por defecto y las convertiría en 500.
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            return problem(errorResponse.getStatusCode(), "bad-request", "Solicitud inválida",
                    errorResponse.getBody().getDetail());
        }
        log.error("Error no controlado en ms-load-allocation: ", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Error interno",
                "No se pudo procesar la solicitud de la carga");
    }

    /** ProblemDetail con los campos de antes (message, error, timestamp) para no romper a los clientes actuales. */
    static ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(title);
        problem.setProperty("error", title);
        problem.setProperty("message", detail);
        problem.setProperty("timestamp", LocalDateTime.now());
        return ResponseEntity.status(status).contentType(negotiatedType()).body(problem);
    }

    /**
     * application/problem+json solo para quien lo pide en Accept. Spring lo usaría también ante Accept:
     * application/json, y la PWA trataría el cuerpo como texto (decide por "application/json" en el Content-Type).
     */
    private static MediaType negotiatedType() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String accept = attributes.getRequest().getHeader("Accept");
            if (accept != null && accept.contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE)) {
                return MediaType.APPLICATION_PROBLEM_JSON;
            }
        }
        return MediaType.APPLICATION_JSON;
    }
}
