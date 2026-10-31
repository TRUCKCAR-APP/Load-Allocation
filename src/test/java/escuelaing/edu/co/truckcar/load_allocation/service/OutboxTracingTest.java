package escuelaing.edu.co.truckcar.load_allocation.service;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** La publicación diferida del outbox continúa la traza y el X-Correlation-Id de la petición que creó el evento. */
class OutboxTracingTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT_SPAN_ID = "00f067aa0ba902b7";

    private final List<SpanData> exported = new CopyOnWriteArrayList<>();
    private SdkTracerProvider provider;
    private Tracer tracer;
    private OutboxTracing tracing;

    @BeforeEach
    void setUp() {
        provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(new CollectingExporter(exported)))
                .build();
        OtelCurrentTraceContext context = new OtelCurrentTraceContext();
        tracer = new OtelTracer(provider.get("test"), context, event -> { },
                new OtelBaggageManager(context, List.of(OutboxTracing.CORRELATION_FIELD), List.of()));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("tracer", tracer);
        tracing = new OutboxTracing(beans.getBeanProvider(Tracer.class));
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    void publicationRunsAsChildOfTheStoredTraceWithItsCorrelationId() {
        AtomicReference<String> traceSeen = new AtomicReference<>();
        AtomicReference<String> correlationSeen = new AtomicReference<>();

        tracing.publishInTrace("00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01", "cid-123",
                "outbox publish load.published", () -> {
                    traceSeen.set(tracer.currentSpan().context().traceId());
                    correlationSeen.set(tracer.getBaggage(OutboxTracing.CORRELATION_FIELD).get());
                });

        assertThat(traceSeen.get()).isEqualTo(TRACE_ID);
        assertThat(correlationSeen.get()).isEqualTo("cid-123");
        assertThat(exported).singleElement().satisfies(span -> {
            assertThat(span.getTraceId()).isEqualTo(TRACE_ID);
            assertThat(span.getParentSpanId()).isEqualTo(PARENT_SPAN_ID);
            assertThat(span.getName()).isEqualTo("outbox publish load.published");
            assertThat(span.getKind()).isEqualTo(SpanKind.PRODUCER);
        });
    }

    @Test
    void capturesTheTraceParentOfTheCurrentSpan() {
        assertThat(tracing.currentTraceParent()).isNull();

        Span span = tracer.nextSpan().name("POST /api/v1/loads").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            assertThat(tracing.currentTraceParent())
                    .isEqualTo("00-" + span.context().traceId() + "-" + span.context().spanId() + "-01");
        } finally {
            span.end();
        }
    }

    @Test
    void eventsWithoutTraceContextArePublishedWithoutParent() {
        AtomicReference<Boolean> ran = new AtomicReference<>(false);

        tracing.publishInTrace(null, null, "outbox publish legacy", () -> ran.set(true));
        tracing.publishInTrace("not-a-traceparent", null, "outbox publish legacy", () -> ran.set(true));

        assertThat(ran.get()).isTrue();
        assertThat(exported).isEmpty();
    }

    private record CollectingExporter(List<SpanData> spans) implements SpanExporter {

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
