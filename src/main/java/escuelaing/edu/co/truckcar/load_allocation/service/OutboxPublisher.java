package escuelaing.edu.co.truckcar.load_allocation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckcar.load_allocation.model.OutboxEvent;
import escuelaing.edu.co.truckcar.load_allocation.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxTracing tracing;

    @Value("${truckcar.kafka.topic.load-events:truckcar.events.load-allocation}")
    private String loadEventsTopic;

    @Value("${truckcar.outbox.batch-size:100}")
    private int batchSize;

    /** Clave del candado del outbox; distinta en cada servicio porque comparten truckcar_db. */
    private static final long PUBLISHER_LOCK_KEY = 7_301_001L;

    @Scheduled(fixedDelayString = "${truckcar.outbox.publish-interval-ms:1000}")
    @Transactional
    public void publishPendingEvents() {
        if (!repository.tryAcquirePublisherLock(PUBLISHER_LOCK_KEY)) {
            return; // otra réplica está publicando este ciclo
        }
        List<OutboxEvent> pending = repository.findByPublishedAtIsNullOrderByCreatedAtAsc(
                PageRequest.of(0, batchSize));
        pending.forEach(this::publish);
    }

    private void publish(OutboxEvent outboxEvent) {
        try {
            JsonNode envelope = objectMapper.readTree(outboxEvent.getPayload());
            sendInOriginalTrace(outboxEvent, envelope);
            markPublished(outboxEvent);
        } catch (JsonProcessingException | InterruptedException | ExecutionException | TimeoutException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            markFailed(outboxEvent, exception);
        }
    }

    private void sendInOriginalTrace(OutboxEvent outboxEvent, JsonNode envelope)
            throws InterruptedException, ExecutionException, TimeoutException {
        Exception[] failure = new Exception[1];
        tracing.publishInTrace(outboxEvent.getTraceParent(), outboxEvent.getCorrelationId(),
                "outbox publish " + outboxEvent.getEventType(), () -> {
                    try {
                        kafkaTemplate.send(loadEventsTopic, outboxEvent.getAggregateId(), envelope).get(10, TimeUnit.SECONDS);
                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                        failure[0] = e;
                    }
                });
        if (failure[0] instanceof InterruptedException interrupted) {
            throw interrupted;
        }
        if (failure[0] instanceof ExecutionException execution) {
            throw execution;
        }
        if (failure[0] instanceof TimeoutException timeout) {
            throw timeout;
        }
    }

    @Transactional
    protected void markPublished(OutboxEvent outboxEvent) {
        repository.markPublished(outboxEvent.getId(), Instant.now());
        log.info("Evento Outbox {} publicado en {}", outboxEvent.getId(), loadEventsTopic);
    }

    @Transactional
    protected void markFailed(OutboxEvent outboxEvent, Exception exception) {
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        repository.markFailed(outboxEvent.getId(), message.substring(0, Math.min(message.length(), 2000)));
        log.error("No se pudo publicar evento Outbox {}; se reintentará", outboxEvent.getId(), exception);
    }
}
