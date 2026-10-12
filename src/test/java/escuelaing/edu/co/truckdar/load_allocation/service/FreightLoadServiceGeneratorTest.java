package escuelaing.edu.co.truckdar.load_allocation.service;

import escuelaing.edu.co.truckdar.load_allocation.dto.request.CreateFreightLoadRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.response.FreightLoadResponse;
import escuelaing.edu.co.truckdar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckdar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckdar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckdar.load_allocation.model.TruckTypeRequired;
import escuelaing.edu.co.truckdar.load_allocation.repository.FreightLoadRepository;
import escuelaing.edu.co.truckdar.load_allocation.repository.ProofOfDeliveryMediaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Panel del generador: la empresa de una carga es el usuario que la publica. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FreightLoadServiceGeneratorTest {

    private static final String GENERATOR = "6d6ebed7-8dc9-4653-81e5-3846e7a6729e";

    @Mock
    private FreightLoadRepository repository;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private ProofOfDeliveryMediaRepository podMediaRepository;

    private FreightLoadService service;

    @BeforeEach
    void setUp() {
        service = new FreightLoadService(repository, outboxEventService, podMediaRepository);
        when(repository.save(any(FreightLoad.class))).thenAnswer(invocation -> {
            FreightLoad load = invocation.getArgument(0);
            load.setId(77L);
            return load;
        });
    }

    private CreateFreightLoadRequest request(String companyIdInBody) {
        return CreateFreightLoadRequest.builder()
                .generatorCompanyId(companyIdInBody)
                .generatorCompanyName("Cementos Andinos")
                .cargoDescription("Cemento ensacado")
                .weightTons(30.0)
                .requiredTruckType(TruckTypeRequired.TRACTOMULA_3S3)
                .originCity("Bogota").originDepartment("Cundinamarca").originLatitude(4.61).originLongitude(-74.08)
                .destinationCity("Cali").destinationDepartment("Valle del Cauca").destinationLatitude(3.45).destinationLongitude(-76.53)
                .offeredBudget(new BigDecimal("5000000"))
                .build();
    }

    @Test
    void publishedLoadBelongsToTheAuthenticatedUserEvenIfTheBodySaysOtherwise() {
        FreightLoadResponse published = service.publishLoad(request("otra-empresa"), UUID.randomUUID(), GENERATOR, "COORDINADOR");

        assertThat(published.getGeneratorCompanyId()).isEqualTo(GENERATOR);
        verify(outboxEventService).enqueueLoadEvent(any(FreightLoad.class), org.mockito.ArgumentMatchers.eq("load.published"));
    }

    @Test
    void driversCannotPublishLoads() {
        assertThatThrownBy(() -> service.publishLoad(request(null), null, "driver-ana", "CONDUCTOR"))
                .isInstanceOf(AssignmentAccessDeniedException.class)
                .hasMessageContaining("COORDINADOR o ADMIN");
        verify(repository, never()).save(any());
    }

    @Test
    void internalCallsWithoutUserKeepTheCompanyFromTheBodyOrAreRejected() {
        assertThat(service.publishLoad(request("empresa-interna"), null, null, null).getGeneratorCompanyId())
                .isEqualTo("empresa-interna");
        assertThatThrownBy(() -> service.publishLoad(request(" "), null, null, null))
                .isInstanceOf(AssignmentAccessDeniedException.class)
                .matches(e -> ((AssignmentAccessDeniedException) e).isUnauthenticated());
    }

    @Test
    void generatorSeesOnlyItsOwnShipments() {
        FreightLoad own = FreightLoad.builder().id(1L).generatorCompanyId(GENERATOR).status(LoadStatus.IN_TRANSIT)
                .requiredTruckType(TruckTypeRequired.TRACTOMULA_3S3).build();
        when(repository.findByGeneratorCompanyIdOrderByPublishedAtDesc(GENERATOR)).thenReturn(List.of(own));

        assertThat(service.getPublishedLoads(GENERATOR, "COORDINADOR")).extracting(FreightLoadResponse::getId).containsExactly(1L);
        assertThat(service.getLoadsByCompany(GENERATOR, GENERATOR, "COORDINADOR")).hasSize(1);
        assertThatThrownBy(() -> service.getPublishedLoads(null, "GENERADOR")).isInstanceOf(AssignmentAccessDeniedException.class);
    }

    @Test
    void anotherCompanysLoadsAreOnlyVisibleToAdmins() {
        when(repository.findByGeneratorCompanyIdOrderByPublishedAtDesc("otra-empresa")).thenReturn(List.of());

        assertThatThrownBy(() -> service.getLoadsByCompany("otra-empresa", GENERATOR, "COORDINADOR"))
                .isInstanceOf(AssignmentAccessDeniedException.class);
        assertThat(service.getLoadsByCompany("otra-empresa", "admin-1", "ADMIN")).isEmpty();
    }

    // ------------------------------------------------------------------ separación del rol GENERADOR

    private FreightLoad ownedLoad(Long id, String owner, LoadStatus status) {
        FreightLoad load = FreightLoad.builder().id(id).generatorCompanyId(owner).status(status)
                .assignedDriverId("driver-ana").requiredTruckType(TruckTypeRequired.TRACTOMULA_3S3).build();
        when(repository.findById(id)).thenReturn(java.util.Optional.of(load));
        return load;
    }

    private static escuelaing.edu.co.truckdar.load_allocation.dto.request.CancelLoadRequest reason() {
        return escuelaing.edu.co.truckdar.load_allocation.dto.request.CancelLoadRequest.builder().reason("Pedido anulado").build();
    }

    @Test
    void generadorPublishesUnderItsOwnId() {
        assertThat(service.publishLoad(request("otra"), null, GENERATOR, "GENERADOR").getGeneratorCompanyId())
                .isEqualTo(GENERATOR);
    }

    @Test
    void generadorCannotSeeTheControlTowerViews() {
        assertThatThrownBy(() -> service.listLoads(null, GENERATOR, "GENERADOR"))
                .isInstanceOf(AssignmentAccessDeniedException.class);
        assertThatThrownBy(() -> service.getAvailableLoads(null, "GENERADOR"))
                .isInstanceOf(AssignmentAccessDeniedException.class);
        assertThatThrownBy(() -> service.getDriverCommitments("GENERADOR"))
                .isInstanceOf(AssignmentAccessDeniedException.class);
        // Servicios internos (sin rol) y torre de control siguen igual.
        assertThat(service.getAvailableLoads(null, null)).isEmpty();
        assertThat(service.getDriverCommitments("COORDINADOR")).isEmpty();
    }

    @Test
    void publishedLoadsAreForPublishersOnly() {
        when(repository.findByGeneratorCompanyIdOrderByPublishedAtDesc(GENERATOR)).thenReturn(List.of());

        assertThat(service.getPublishedLoads(GENERATOR, "GENERADOR")).isEmpty();
        assertThatThrownBy(() -> service.getPublishedLoads("driver-ana", "CONDUCTOR"))
                .isInstanceOf(AssignmentAccessDeniedException.class);
    }

    @Test
    void generadorReadsOnlyItsOwnLoads() {
        ownedLoad(5L, GENERATOR, LoadStatus.AVAILABLE);
        ownedLoad(6L, "otra-empresa", LoadStatus.AVAILABLE);

        assertThat(service.getLoadById(5L, GENERATOR, "GENERADOR").getId()).isEqualTo(5L);
        assertThatThrownBy(() -> service.getLoadById(6L, GENERATOR, "GENERADOR"))
                .isInstanceOf(AssignmentAccessDeniedException.class);
        assertThat(service.getLoadById(6L, null, null).getId()).isEqualTo(6L);
        assertThat(service.getLoadById(6L, "driver-ana", "CONDUCTOR").getId()).isEqualTo(6L);
    }

    @Test
    void generadorCancelsOnlyItsOwnLoadsBeforeTheTripStarts() {
        FreightLoad own = ownedLoad(5L, GENERATOR, LoadStatus.ASSIGNED);
        ownedLoad(6L, "otra-empresa", LoadStatus.AVAILABLE);
        ownedLoad(7L, GENERATOR, LoadStatus.IN_TRANSIT);

        assertThatThrownBy(() -> service.cancelLoad(6L, GENERATOR, "GENERADOR", reason()))
                .isInstanceOf(AssignmentAccessDeniedException.class);
        assertThatThrownBy(() -> service.cancelLoad(7L, GENERATOR, "GENERADOR", reason()))
                .isInstanceOf(escuelaing.edu.co.truckdar.load_allocation.exception.InvalidLoadOperationException.class);
        assertThat(service.cancelLoad(5L, GENERATOR, "GENERADOR", reason()).getStatus()).isEqualTo(LoadStatus.CANCELLED);
        assertThat(own.getCancelledBy()).isEqualTo(GENERATOR);
    }

    @Test
    void proofOfDeliveryIsVisibleToTheOwningGenerador() {
        ownedLoad(5L, GENERATOR, LoadStatus.DELIVERED);
        ownedLoad(6L, "otra-empresa", LoadStatus.DELIVERED);

        assertThat(service.getProofOfDelivery(5L, GENERATOR, "GENERADOR").getLoadId()).isEqualTo(5L);
        assertThatThrownBy(() -> service.getProofOfDelivery(6L, GENERATOR, "GENERADOR"))
                .isInstanceOf(AssignmentAccessDeniedException.class);
    }
}
