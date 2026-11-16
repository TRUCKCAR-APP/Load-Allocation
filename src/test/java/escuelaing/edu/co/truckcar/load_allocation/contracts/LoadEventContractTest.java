package escuelaing.edu.co.truckcar.load_allocation.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckcar.load_allocation.config.JacksonConfig;
import escuelaing.edu.co.truckcar.load_allocation.listener.AssignmentNotificationListener;
import escuelaing.edu.co.truckcar.load_allocation.listener.UserAccountEventListener;
import escuelaing.edu.co.truckcar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckcar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.OutboxEvent;
import escuelaing.edu.co.truckcar.load_allocation.model.TruckTypeRequired;
import escuelaing.edu.co.truckcar.load_allocation.repository.DriverAccountStatusRepository;
import escuelaing.edu.co.truckcar.load_allocation.repository.OutboxEventRepository;
import escuelaing.edu.co.truckcar.load_allocation.service.IFreightLoadService;
import escuelaing.edu.co.truckcar.load_allocation.service.OutboxEventService;
import escuelaing.edu.co.truckcar.load_allocation.service.OutboxTracing;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contrato de los eventos de carga entre Load-Allocation (productor) y sus consumidores (AI-fleet, fleets-drivers,
 * Notifications). El fixture contracts/load-allocation.load-event.v1.json es la forma acordada: los consumidores lo
 * usan en sus pruebas. Si esta prueba falla, cambió el evento: actualice el fixture aquí y en los consumidores.
 */
class LoadEventContractTest {

    private final ObjectMapper mapper = new JacksonConfig().objectMapper();

    static JsonNode fixture(ObjectMapper mapper, String name) throws IOException {
        try (InputStream in = LoadEventContractTest.class.getResourceAsStream("/contracts/" + name)) {
            return mapper.readTree(in);
        }
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(keys::add);
        return keys;
    }

    @Test
    void theEventThatTheOutboxPublishesHasExactlyTheAgreedShape() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        OutboxTracing tracing = mock(OutboxTracing.class);
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        when(repository.save(saved.capture())).thenAnswer(i -> i.getArgument(0));
        FreightLoad load = FreightLoad.builder().id(95L).generatorCompanyId("gen-1").cargoDescription("Café en sacos")
                .weightTons(20.0).requiredTruckType(TruckTypeRequired.TRACTOMULA_3S3).originCity("Bogotá")
                .destinationCity("Tunja").offeredBudget(new BigDecimal("2500000")).status(LoadStatus.AVAILABLE)
                .assignmentStatus(AssignmentStatus.PROPOSED).assignedDriverId("driver-1").assignedDriverName("Ana Pérez")
                .assignedVehiclePlate("TDK-410").assignmentAttempts(1).discardedDriverIds(new HashSet<>(Set.of("driver-0")))
                .build();
        load.setProposedBy("TORRE");

        new OutboxEventService(repository, mapper, tracing).enqueueLoadEvent(load, "assignment.proposed");

        JsonNode published = mapper.readTree(saved.getValue().getPayload());
        JsonNode agreed = fixture(mapper, "load-allocation.load-event.v1.json");
        assertThat(keys(published)).as("sobre").isEqualTo(keys(agreed));
        assertThat(keys(published.get("payload"))).as("payload").isEqualTo(keys(agreed.get("payload")));
        assertThat(published.get("payload").get("proposedBy").asText()).isEqualTo("TORRE");
    }

    /** Lector tolerante (Bloque 1): un campo nuevo del productor no manda el evento a la cola de muertos. */
    @Test
    void anAssignmentNotifiedWithUnknownFieldsStillMovesTheLoadToNotified() throws Exception {
        IFreightLoadService service = mock(IFreightLoadService.class);
        when(service.applyAssignmentNotified(any(), any())).thenReturn(true);
        com.fasterxml.jackson.databind.node.ObjectNode event =
                (com.fasterxml.jackson.databind.node.ObjectNode) fixture(mapper, "load-allocation.load-event.v1.json");
        event.put("eventType", "assignment.notified").put("producer", "notifications").put("campoFuturoDelSobre", "x");
        ((com.fasterxml.jackson.databind.node.ObjectNode) event.get("payload")).put("campoFuturo", 1);

        new AssignmentNotificationListener(service, mapper).handleAssignmentNotification(mapper.writeValueAsString(event));

        verify(service).applyAssignmentNotified(95L, "driver-1");
    }

    @Test
    void aUserEventWithUnknownFieldsIsStillApplied() {
        DriverAccountStatusRepository accounts = mock(DriverAccountStatusRepository.class);
        new UserAccountEventListener(accounts, mapper).handleUserEvent("""
                {"eventId":"e1","eventType":"user.status.changed","nuevoCampo":true,
                 "payload":{"userId":"ana","status":"ACTIVE","previousStatus":null,"otroCampo":[1,2]}}""");

        verify(accounts).save(any());
    }
}
