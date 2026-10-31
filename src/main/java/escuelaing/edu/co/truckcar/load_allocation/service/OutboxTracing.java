package escuelaing.edu.co.truckcar.load_allocation.service;

import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Continuidad de la traza a través del outbox. El evento se publica en Kafka en otro hilo y segundos después de la
 * petición que lo originó; sin esto cada publicación abriría una traza nueva. Al encolar se guarda el traceparent W3C
 * del span actual (y el X-Correlation-Id de la petición) y al publicar se envía como hijo de esa traza, de modo que el
 * consumidor en el otro servicio continúa la misma traza.
 */
@Component
public class OutboxTracing {

    static final String CORRELATION_FIELD = "X-Correlation-Id";
    private static final Pattern TRACE_PARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");

    private final ObjectProvider<Tracer> tracer;

    public OutboxTracing(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    /** traceparent W3C del span actual, o null fuera de una traza. */
    public String currentTraceParent() {
        Tracer active = tracer.getIfAvailable();
        Span span = active == null ? null : active.currentSpan();
        if (span == null) {
            return null;
        }
        TraceContext context = span.context();
        return "00-" + context.traceId() + "-" + context.spanId() + "-" + (Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
    }

    /** X-Correlation-Id de la petición en curso (baggage), o null. */
    public String currentCorrelationId() {
        Tracer active = tracer.getIfAvailable();
        if (active == null) {
            return null;
        }
        Baggage baggage = active.getBaggage(CORRELATION_FIELD);
        String value = baggage == null ? null : baggage.get();
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Ejecuta la publicación dentro de un span hijo de la traza guardada. Sin traceparent válido (eventos anteriores
     * a este campo) se publica sin padre y el productor de Kafka abre su propia traza.
     */
    public void publishInTrace(String traceParent, String correlationId, String spanName, Runnable publication) {
        Tracer active = tracer.getIfAvailable();
        var parsed = traceParent == null ? null : TRACE_PARENT.matcher(traceParent);
        if (active == null || parsed == null || !parsed.matches()) {
            publication.run();
            return;
        }
        TraceContext parent = active.traceContextBuilder()
                .traceId(parsed.group(1))
                .spanId(parsed.group(2))
                .sampled("01".equals(parsed.group(3)))
                .build();
        Span span = active.spanBuilder().setParent(parent).name(spanName).kind(Span.Kind.PRODUCER).start();
        if (correlationId != null) {
            span.tag(CORRELATION_FIELD, correlationId);
        }
        try (Tracer.SpanInScope ignored = active.withSpan(span);
             BaggageInScope baggage = correlationId == null ? null : active.createBaggageInScope(CORRELATION_FIELD, correlationId)) {
            publication.run();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
