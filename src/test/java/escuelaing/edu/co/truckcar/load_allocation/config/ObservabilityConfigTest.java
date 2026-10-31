package escuelaing.edu.co.truckcar.load_allocation.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.ReadableSpan;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityConfigTest {

    private static final AttributeKey<String> CORRELATION_ID = AttributeKey.stringKey("X-Correlation-Id");

    private final ObservabilityConfig config = new ObservabilityConfig();

    @Test
    void everySpanCarriesTheCorrelationIdFromTheParentBaggage() {
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(config.correlationIdSpanProcessor())
                .build()) {
            Context parent = Context.root().with(Baggage.builder().put("X-Correlation-Id", "cid-42").build());

            var tagged = provider.get("test").spanBuilder("http post /api/v1/loads").setParent(parent).startSpan();
            var untagged = provider.get("test").spanBuilder("sin baggage").setParent(Context.root()).startSpan();

            assertThat(((ReadableSpan) tagged).getAttribute(CORRELATION_ID)).isEqualTo("cid-42");
            assertThat(((ReadableSpan) untagged).getAttribute(CORRELATION_ID)).isNull();
            tagged.end();
            untagged.end();
        }
    }

    @Test
    void entrySpansTakeTheCorrelationIdFromTheRequestHeader() {
        ServerRequestObservationContext request = serverRequest("/api/v1/fleets-drivers/sync/batch");
        ((MockHttpServletRequest) request.getCarrier()).addHeader("X-Correlation-Id", "cid-sync-7");

        config.correlationIdOfEntrySpans().map(request);

        assertThat(request.getHighCardinalityKeyValue("X-Correlation-Id")).isEqualTo(KeyValue.of("X-Correlation-Id", "cid-sync-7"));
        assertThat(request.getLowCardinalityKeyValue("X-Correlation-Id")).isNull();
    }

    @Test
    void actuatorScrapesAndPollingTasksAreNotTraced() {
        ObservationPredicate predicate = config.skipInfrastructureObservations();

        assertThat(predicate.test("http.server.requests", serverRequest("/actuator/prometheus"))).isFalse();
        assertThat(predicate.test("http.server.requests", serverRequest("/api/v1/loads"))).isTrue();
        assertThat(predicate.test("tasks.scheduled.execution", new Observation.Context())).isFalse();
        assertThat(predicate.test("spring.kafka.listener", new Observation.Context())).isTrue();
    }

    private static ServerRequestObservationContext serverRequest(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        return new ServerRequestObservationContext(request, new MockHttpServletResponse());
    }
}
