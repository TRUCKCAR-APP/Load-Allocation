package escuelaing.edu.co.truckcar.load_allocation.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckcar.load_allocation.dto.event.EventEnvelope;
import escuelaing.edu.co.truckcar.load_allocation.service.IFreightLoadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssignmentNotificationListener {

    private final IFreightLoadService freightLoadService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${truckcar.kafka.topic.assignment-notifications:truckcar.events.assignment-notifications}",
            groupId = "${spring.kafka.consumer.group-id:load-allocation-group}"
    )
    public void handleAssignmentNotification(String message) {
        EventEnvelope<JsonNode> event;
        try {
            event = objectMapper.readValue(message, objectMapper.getTypeFactory()
                    .constructParametricType(EventEnvelope.class, JsonNode.class));
        } catch (Exception e) {
            throw new IllegalStateException("Evento de notificación de asignación inválido; se requiere reintento o DLQ", e);
        }
        if (!"assignment.notified".equals(event.getEventType()) || event.getPayload() == null) {
            log.debug("Evento de notificación ignorado: type='{}', eventId='{}'",
                    event.getEventType(), event.getEventId());
            return;
        }

        JsonNode payload = event.getPayload();
        if (!payload.path("loadId").canConvertToLong()) {
            throw new IllegalStateException("assignment.notified sin loadId válido: eventId=" + event.getEventId());
        }
        Long loadId = payload.path("loadId").asLong();
        String driverId = payload.hasNonNull("assignedDriverId") ? payload.get("assignedDriverId").asText() : null;

        if (freightLoadService.applyAssignmentNotified(loadId, driverId)) {
            log.info("Carga #{} avanzó a NOTIFIED por evento {}", loadId, event.getEventId());
        }
    }
}
