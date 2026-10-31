package escuelaing.edu.co.truckcar.load_allocation.service;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Métricas de negocio de la asignación (Prometheus, registro global de Micrometer).
 * <ul>
 *   <li>truckcar_load_assignment_latency_seconds{stage="proposed"}: de la publicación de la carga a la primera
 *       propuesta de conductor; es la latencia de extremo a extremo del matching (outbox, Kafka, AI-fleet y vuelta).</li>
 *   <li>stage="accepted": de la publicación a la aceptación del conductor.</li>
 *   <li>truckcar_load_assignment_outcomes_total{outcome}: propuestas, aceptaciones, rechazos, vencimientos y cargas
 *       que quedan sin conductor.</li>
 * </ul>
 */
final class AssignmentMetrics {

    private AssignmentMetrics() {
    }

    static void recordLatency(String stage, LocalDateTime publishedAt) {
        if (publishedAt == null) {
            return;
        }
        Duration elapsed = Duration.between(publishedAt, LocalDateTime.now());
        if (elapsed.isNegative()) {
            return;
        }
        Timer.builder("truckcar.load.assignment.latency")
                .description("Tiempo desde la publicación de la carga hasta cada etapa de la asignación")
                .tag("stage", stage)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(50))
                .maximumExpectedValue(Duration.ofHours(2))
                .register(Metrics.globalRegistry)
                .record(elapsed);
    }

    static void countOutcome(String outcome) {
        Metrics.counter("truckcar.load.assignment.outcomes", "outcome", outcome).increment();
    }
}
