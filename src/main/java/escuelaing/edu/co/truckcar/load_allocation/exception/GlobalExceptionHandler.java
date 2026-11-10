package escuelaing.edu.co.truckcar.load_allocation.exception;

import lombok.extern.slf4j.Slf4j;
import escuelaing.edu.co.truckcar.load_allocation.tracking.TrackingLinkUnavailableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import software.amazon.awssdk.core.exception.SdkException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(FreightLoadNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(FreightLoadNotFoundException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", LocalDateTime.now());
        error.put("status", HttpStatus.NOT_FOUND.value());
        error.put("error", "Carga no encontrada");
        error.put("message", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    /** Enlace público inválido, vencido o de un envío cancelado/entregado: 410 sin detalles de la carga. */
    @ExceptionHandler(TrackingLinkUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleTrackingUnavailable(TrackingLinkUnavailableException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", LocalDateTime.now());
        error.put("status", HttpStatus.GONE.value());
        error.put("error", "Seguimiento no disponible");
        error.put("message", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.GONE);
    }

    @ExceptionHandler(InvalidLoadOperationException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidOperation(InvalidLoadOperationException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", LocalDateTime.now());
        error.put("status", HttpStatus.CONFLICT.value());
        error.put("error", "Operación inválida sobre la carga");
        error.put("message", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(ProofOfDeliveryEvidenceException.class)
    public ResponseEntity<Map<String, Object>> handleEvidence(ProofOfDeliveryEvidenceException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", LocalDateTime.now());
        error.put("status", HttpStatus.UNPROCESSABLE_ENTITY.value());
        error.put("error", "Evidencia de entrega inválida");
        error.put("message", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @ExceptionHandler(AssignmentAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AssignmentAccessDeniedException ex) {
        HttpStatus status = ex.isUnauthenticated() ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", LocalDateTime.now());
        error.put("status", status.value());
        error.put("error", ex.isUnauthenticated() ? "Usuario no autenticado" : "Operación no permitida");
        error.put("message", ex.getMessage());
        return new ResponseEntity<>(error, status);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, Object> error = new HashMap<>();
        Map<String, String> fieldErrors = new HashMap<>();

        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }

        error.put("timestamp", LocalDateTime.now());
        error.put("status", HttpStatus.BAD_REQUEST.value());
        error.put("error", "Validación de datos fallida");
        // message como en el resto de errores: el cliente siempre tiene un texto que mostrar.
        error.put("message", fieldErrors.isEmpty() ? "La solicitud tiene datos inválidos"
                : String.join("; ", new java.util.TreeMap<>(fieldErrors).values()));
        error.put("details", fieldErrors);
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /** JSON mal formado, UTF-8 inválido o un valor que no corresponde al tipo (enum desconocido): error del cliente. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Cuerpo ilegible: {}", ex.getMessage());
        // Sin el detalle del parser en la respuesta: puede incluir fragmentos del cuerpo y nombres de clases internas.
        return clientError(HttpStatus.BAD_REQUEST, "Cuerpo de la solicitud inválido",
                "El cuerpo debe ser JSON válido en UTF-8 con los campos y valores esperados");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return clientError(HttpStatus.BAD_REQUEST, "Parámetro inválido", ex.getName() + ": valor con un tipo incorrecto");
    }

    private static ResponseEntity<Map<String, Object>> clientError(HttpStatusCode status, String error, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        return new ResponseEntity<>(body, status);
    }

    /**
     * Dos operaciones simultáneas sobre la misma carga (p. ej. el conductor acepta justo cuando vence la propuesta):
     * gana la primera y la otra recibe 409 para que el cliente vuelva a consultar, en lugar de un 500.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleConcurrentChange(ObjectOptimisticLockingFailureException ex) {
        log.info("Cambio concurrente sobre una carga: {}", ex.getMessage());
        return clientError(HttpStatus.CONFLICT, "Operación inválida sobre la carga",
                "La carga cambió mientras se procesaba la solicitud; consulte su estado e intente de nuevo");
    }

    /** Un dato que la base rechaza (duplicado, demasiado largo...) es un conflicto con la solicitud, no un fallo interno. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Datos rechazados por la base: {}", ex.getMostSpecificCause().getMessage());
        return clientError(HttpStatus.CONFLICT, "Datos inválidos",
                "Los datos enviados no se pudieron guardar; revise la longitud y el formato de los campos");
    }

    /** Almacenamiento de evidencias (S3) caído o sin permisos: 503 para que la PWA reintente la entrega. */
    @ExceptionHandler(SdkException.class)
    public ResponseEntity<Map<String, Object>> handleStorageUnavailable(SdkException ex) {
        log.error("Almacenamiento de evidencias no disponible: {}", ex.getMessage());
        return clientError(HttpStatus.SERVICE_UNAVAILABLE, "Servicio no disponible",
                "El almacenamiento de evidencias no responde; intente de nuevo en unos minutos");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        // Excepciones de Spring MVC con su propio estado (405, 415, parámetro faltante...): este handler genérico las
        // capturaría antes que el resolver por defecto y las convertiría en 500.
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            return clientError(errorResponse.getStatusCode(), "Solicitud inválida", errorResponse.getBody().getDetail());
        }
        log.error("Error no controlado en ms-load-allocation: ", ex);
        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", LocalDateTime.now());
        error.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        error.put("error", "Error interno");
        error.put("message", "No se pudo procesar la solicitud de la carga");
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}