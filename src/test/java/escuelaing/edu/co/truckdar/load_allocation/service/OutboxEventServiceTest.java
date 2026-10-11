package escuelaing.edu.co.truckdar.load_allocation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckdar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckdar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckdar.load_allocation.model.TruckTypeRequired;
import escuelaing.edu.co.truckdar.load_allocation.model.OutboxEvent;
import escuelaing.edu.co.truckdar.load_allocation.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxEventServiceTest {

    @Mock
    private OutboxEventRepository repository;

    @Test
    void enqueuesSerializedEnvelopeForTheSameLoadTransaction() throws Exception {
        OutboxEventService service = new OutboxEventService(repository, new ObjectMapper()
                .findAndRegisterModules());
        FreightLoad load = FreightLoad.builder()
                .id(42L)
                .generatorCompanyId("company-1")
                .cargoDescription("Steel")
                .weightTons(30.0)
                .requiredTruckType(TruckTypeRequired.TRACTOMULA_3S3)
                .originCity("Bogota")
                .destinationCity("Cali")
                .offeredBudget(new BigDecimal("1500000"))
                .status(LoadStatus.AVAILABLE)
                .publishedAt(LocalDateTime.now())
                .build();

        service.enqueueLoadEvent(load, "load.published");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo("load.published");
        assertThat(event.getAggregateId()).isEqualTo("42");
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getPayload()).contains("\"eventType\":\"load.published\"");
        assertThat(event.getPayload()).contains("\"loadId\":42");
    }

    @Test
    void reusesLoadCorrelationIdForEveryEventOfTheLoad() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        OutboxEventService service = new OutboxEventService(repository, mapper);
        UUID correlationId = UUID.randomUUID();
        FreightLoad load = sampleLoad();
        load.setCorrelationId(correlationId);

        for (String eventType : List.of("load.published", "assignment.proposed", "assignment.notified",
                "assignment.rejected", "assignment.timeout")) {
            service.enqueueLoadEvent(load, eventType);
        }

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, times(5)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(event -> mapper.readTree(event.getPayload()).get("correlationId").asText())
                .containsOnly(correlationId.toString());
    }

    @Test
    void assignsAndKeepsCorrelationIdForLoadsCreatedBeforeTheField() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        OutboxEventService service = new OutboxEventService(repository, mapper);
        FreightLoad legacyLoad = sampleLoad();

        service.enqueueLoadEvent(legacyLoad, "assignment.rejected");
        service.enqueueLoadEvent(legacyLoad, "assignment.proposed");

        assertThat(legacyLoad.getCorrelationId()).isNotNull();
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(event -> mapper.readTree(event.getPayload()).get("correlationId").asText())
                .containsOnly(legacyLoad.getCorrelationId().toString());
    }

    private FreightLoad sampleLoad() {
        return FreightLoad.builder()
                .id(42L)
                .generatorCompanyId("company-1")
                .cargoDescription("Steel")
                .weightTons(30.0)
                .requiredTruckType(TruckTypeRequired.TRACTOMULA_3S3)
                .originCity("Bogota")
                .destinationCity("Cali")
                .offeredBudget(new BigDecimal("1500000"))
                .status(LoadStatus.AVAILABLE)
                .publishedAt(LocalDateTime.now())
                .build();
    }
}
