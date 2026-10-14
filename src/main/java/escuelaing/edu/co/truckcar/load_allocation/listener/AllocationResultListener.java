package escuelaing.edu.co.truckcar.load_allocation.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckcar.load_allocation.service.IFreightLoadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consume las recomendaciones del motor de asignación para cerrar el bucle de reasignación:
 * agent.score.ready vuelve a proponer la carga y agent.no.candidates la marca sin conductor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AllocationResultListener {

    private final IFreightLoadService freightLoadService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${truckcar.kafka.topic.allocation-results:truckcar.events.fleet-allocation-results}",
            groupId = "${spring.kafka.consumer.group-id:load-allocation-group}"
    )
    public void handleAllocationResult(String message) {
        JsonNode event;
        try {
            event = objectMapper.readTree(message);
        } catch (Exception e) {
            throw new IllegalStateException("Resultado de asignación inválido; se requiere reintento o DLQ", e);
        }
        // El tópico también transporta FleetAllocationCompletedEvent sin sobre común; no aplica aquí.
        String eventType = event.path("eventType").asText(null);
        JsonNode payload = event.path("payload");
        if (eventType == null || !payload.path("loadId").canConvertToLong()) {
            log.debug("Resultado de asignación sin sobre común ignorado");
            return;
        }
        Long loadId = payload.path("loadId").asLong();

        if ("agent.score.ready".equals(eventType)) {
            freightLoadService.proposeReassignment(loadId,
                    text(payload, "driverId"), text(payload, "driverName"), text(payload, "vehiclePlate"));
        } else if ("agent.no.candidates".equals(eventType)) {
            freightLoadService.markNoDriversAvailable(loadId);
        } else {
            log.debug("Resultado de asignación ignorado: type='{}'", eventType);
        }
    }

    private String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
