package escuelaing.edu.co.truckcar.load_allocation.model;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Normaliza el header X-Correlation-Id al UUID que viaja en el sobre de eventos. */
public final class CorrelationIds {

    public static final String HEADER = "X-Correlation-Id";

    private CorrelationIds() {
    }

    /**
     * Devuelve el UUID del header; si no es un UUID se deriva uno determinístico,
     * y si viene vacío se genera uno nuevo.
     */
    public static UUID fromHeader(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return UUID.randomUUID();
        }
        String value = headerValue.trim();
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notUuid) {
            return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
