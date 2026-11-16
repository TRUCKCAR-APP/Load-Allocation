package escuelaing.edu.co.truckcar.load_allocation.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import escuelaing.edu.co.truckcar.load_allocation.config.JacksonConfig;
import escuelaing.edu.co.truckcar.load_allocation.listener.AssignmentNotificationListener;
import escuelaing.edu.co.truckcar.load_allocation.service.IFreightLoadService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bloque 3: la cadena de asignación por un Kafka real. Notifications publica assignment.notified con el payload de
 * Load-Allocation (y puede sumar campos); Load-Allocation lo consume con la misma configuración que en producción
 * (StringDeserializer + el ObjectMapper de JacksonConfig) y la carga avanza a NOTIFIED. Sin Docker se omite.
 */
@Testcontainers(disabledWithoutDocker = true)
class AssignmentNotifiedKafkaIT {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    private static final String TOPIC = "truckcar.events.assignment-notifications";

    @Test
    void anAssignmentNotifiedFromNotificationsWithExtraFieldsCrossesKafkaAndAdvancesTheLoad() throws Exception {
        ObjectMapper mapper = new JacksonConfig().objectMapper();
        ObjectNode event = (ObjectNode) LoadEventContractTest.fixture(mapper, "load-allocation.load-event.v1.json");
        event.put("eventType", "assignment.notified").put("producer", "notifications").put("traceParent", "00-abc-def-01");
        ((ObjectNode) event.get("payload")).put("notificationChannel", "PUSH");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(TOPIC, "95", mapper.writeValueAsString(event))).get();
        }

        IFreightLoadService service = mock(IFreightLoadService.class);
        when(service.applyAssignmentNotified(any(), any())).thenReturn(true);
        AssignmentNotificationListener listener = new AssignmentNotificationListener(service, mapper);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "load-allocation-group",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(TOPIC));
            ConsumerRecords<String, String> records = ConsumerRecords.empty();
            for (int i = 0; i < 20 && records.isEmpty(); i++) {
                records = consumer.poll(Duration.ofMillis(500));
            }
            assertThat(records.count()).isEqualTo(1);
            records.forEach(record -> listener.handleAssignmentNotification(record.value()));
        }

        verify(service).applyAssignmentNotified(95L, "driver-1");
    }
}
