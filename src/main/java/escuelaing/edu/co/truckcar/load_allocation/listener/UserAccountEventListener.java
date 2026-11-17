package escuelaing.edu.co.truckcar.load_allocation.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckcar.load_allocation.model.DriverAccountStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.DriverAffiliation;
import escuelaing.edu.co.truckcar.load_allocation.repository.DriverAccountStatusRepository;
import escuelaing.edu.co.truckcar.load_allocation.repository.DriverAffiliationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Estado de la cuenta de los conductores a partir de los eventos de user-identity, igual que fleets-drivers:
 * <ul>
 *   <li>user.created de un CONDUCTOR: la cuenta nace PENDING_VERIFICATION.</li>
 *   <li>user.status.changed: verificación (ACTIVE), suspensión o reactivación, y la fotografía que user-identity
 *   publica al arrancar (AccountStatusSnapshotPublisher). El grupo empieza en earliest.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserAccountEventListener {

    private final DriverAccountStatusRepository accounts;
    private final DriverAffiliationRepository affiliations;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${truckcar.kafka.topic.user-events:truckcar.events.user-events}",
            groupId = "${spring.kafka.consumer.group-id:load-allocation-group}")
    @Transactional
    public void handleUserEvent(String message) {
        JsonNode event;
        try {
            event = objectMapper.readTree(message);
        } catch (JsonProcessingException e) {
            log.warn("Evento de usuario descartado: no es JSON válido ({})", e.getOriginalMessage());
            return;
        }
        String type = event.path("eventType").asText("");
        JsonNode payload = event.path("payload");
        String userId = text(payload, "userId");
        if (userId == null) {
            return;
        }
        if ("user.affiliation.changed".equals(type)) {
            String driverType = text(payload, "driverType");
            if (driverType != null) {
                boolean eligible = payload.path("loadEligible").asBoolean(false);
                affiliations.save(new DriverAffiliation(userId, driverType, text(payload, "companyId"),
                        text(payload, "companyName"), text(payload, "affiliationStatus"), eligible, Instant.now()));
                log.info("Afiliación de {}: {} (cargas: {})", userId, driverType, eligible);
            }
        } else if ("user.status.changed".equals(type)) {
            String status = text(payload, "status");
            if (status != null) {
                accounts.save(new DriverAccountStatus(userId, status, Instant.now()));
                log.info("Cuenta de {}: {}", userId, status);
            }
        } else if ("user.created".equals(type) && "CONDUCTOR".equals(text(payload, "role"))
                && !accounts.existsById(userId)) {
            // Si ya llegó un estado (p. ej. se verificó antes de procesar este evento), no se pisa.
            accounts.save(new DriverAccountStatus(userId, "PENDING_VERIFICATION", Instant.now()));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
