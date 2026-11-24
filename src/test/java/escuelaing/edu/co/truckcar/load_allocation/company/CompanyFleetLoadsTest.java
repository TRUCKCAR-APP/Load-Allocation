package escuelaing.edu.co.truckcar.load_allocation.company;

import escuelaing.edu.co.truckcar.load_allocation.dto.request.AssignDriverRequest;
import escuelaing.edu.co.truckcar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckcar.load_allocation.exception.GlobalExceptionHandler;
import escuelaing.edu.co.truckcar.load_allocation.exception.InvalidLoadOperationException;
import escuelaing.edu.co.truckcar.load_allocation.model.DriverAffiliation;
import escuelaing.edu.co.truckcar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckcar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.TruckTypeRequired;
import escuelaing.edu.co.truckcar.load_allocation.repository.DriverAffiliationRepository;
import escuelaing.edu.co.truckcar.load_allocation.repository.FreightLoadRepository;
import escuelaing.edu.co.truckcar.load_allocation.repository.ProofOfDeliveryMediaRepository;
import escuelaing.edu.co.truckcar.load_allocation.service.FreightLoadService;
import escuelaing.edu.co.truckcar.load_allocation.service.OutboxEventService;
import escuelaing.edu.co.truckcar.load_allocation.service.ProofOfDeliveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cargas de la transportadora: las de su flota y las propias, que solo pueden llevar sus conductores. */
class CompanyFleetLoadsTest {

    private final FreightLoadRepository repository = mock(FreightLoadRepository.class);
    private final DriverAffiliationRepository affiliations = mock(DriverAffiliationRepository.class);
    private final OutboxEventService outbox = mock(OutboxEventService.class);
    private final UserIdentityCompanyClient userIdentity = mock(UserIdentityCompanyClient.class);
    private FreightLoadService service;
    private MockMvc mvc;

    private static DriverAffiliation affiliation(String driverId, String companyId) {
        return new DriverAffiliation(driverId, "EMPRESA", companyId, "Empresa " + companyId, "ACTIVA", true, Instant.now());
    }

    @BeforeEach
    void setUp() throws Exception {
        service = new FreightLoadService(repository, outbox, mock(ProofOfDeliveryMediaRepository.class));
        Method setter = FreightLoadService.class.getDeclaredMethod("setAffiliations", DriverAffiliationRepository.class);
        setter.setAccessible(true);
        setter.invoke(service, affiliations);
        mvc = MockMvcBuilders.standaloneSetup(new CompanyFleetLoadsController(service, mock(ProofOfDeliveryService.class), userIdentity))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(userIdentity.myCompany("Bearer andinos")).thenReturn(new UserIdentityCompanyClient.Company("c-andinos", "Transportes Andinos"));
        when(affiliations.findByCompanyIdAndAffiliationStatus("c-andinos", "ACTIVA"))
                .thenReturn(List.of(affiliation("andres", "c-andinos"), affiliation("laura", "c-andinos")));
        when(affiliations.findById("andres")).thenReturn(Optional.of(affiliation("andres", "c-andinos")));
        when(affiliations.findById("jorge")).thenReturn(Optional.of(affiliation("jorge", "c-pacifico")));
        when(repository.save(any(FreightLoad.class))).thenAnswer(inv -> {
            FreightLoad l = inv.getArgument(0);
            if (l.getId() == null) l.setId(77L);
            return l;
        });
    }

    @Test
    void theCompanySeesTheLoadsOfItsOwnDriversAndItsOwnLoads() throws Exception {
        FreightLoad carried = FreightLoad.builder().id(5L).status(LoadStatus.IN_TRANSIT).assignedDriverId("andres")
                .publishedAt(LocalDateTime.now()).build();
        when(repository.findCompanyFleetLoads(eq("c-andinos"), anyCollection(), any())).thenReturn(List.of(carried));

        mvc.perform(get("/api/v1/loads/fleet").header("X-Auth-User", "dueno").header("X-Auth-Role", "TRANSPORTADORA")
                        .header("Authorization", "Bearer andinos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(5));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> drivers = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(repository).findCompanyFleetLoads(eq("c-andinos"), drivers.capture(), any());
        assertThat(drivers.getValue()).containsExactlyInAnyOrder("andres", "laura");
    }

    @Test
    void otherRolesAndAnonymousAreRejected() throws Exception {
        mvc.perform(get("/api/v1/loads/fleet").header("X-Auth-User", "g").header("X-Auth-Role", "GENERADOR")
                .header("Authorization", "Bearer andinos")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/loads/fleet")).andExpect(status().isUnauthorized());
    }

    @Test
    void anOwnLoadIsPublishedForTheCompanyConfirmedByUserIdentity() throws Exception {
        String body = """
                {"generatorCompanyName":"ignorado","cargoDescription":"Cemento","weightTons":20,"requiredTruckType":"TRACTOMULA_3S3",
                 "originCity":"Funza","originDepartment":"Cundinamarca","originLatitude":4.71,"originLongitude":-74.21,"destinationCity":"Ibagué","destinationDepartment":"Tolima",
                 "destinationLatitude":4.43,"destinationLongitude":-75.23,"offeredBudget":2000000}""";
        mvc.perform(post("/api/v1/loads/fleet").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Auth-User", "dueno").header("X-Auth-Role", "TRANSPORTADORA").header("Authorization", "Bearer andinos"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerCompanyId").value("c-andinos"))
                .andExpect(jsonPath("$.generatorCompanyName").value("Transportes Andinos"))
                .andExpect(jsonPath("$.generatorCompanyId").value("dueno"));
        verify(outbox).enqueueLoadEvent(any(FreightLoad.class), eq("load.published"));
    }

    @Test
    void anOwnLoadIsOnlyProposedToTheCompanysDrivers() {
        FreightLoad own = FreightLoad.builder().id(9L).status(LoadStatus.AVAILABLE).ownerCompanyId("c-andinos")
                .generatorCompanyName("Transportes Andinos").requiredTruckType(TruckTypeRequired.TRACTOMULA_3S3)
                .offeredBudget(BigDecimal.ONE).build();
        when(repository.findById(9L)).thenReturn(Optional.of(own));

        assertThatThrownBy(() -> service.assignDriver(9L, AssignDriverRequest.builder().driverId("jorge").driverName("Jorge").build(), "COORDINADOR"))
                .isInstanceOf(InvalidLoadOperationException.class)
                .hasMessageContaining("es propia de Transportes Andinos");
        assertThat(service.assignDriver(9L, AssignDriverRequest.builder().driverId("andres").driverName("Andrés").build(), "SYSTEM")
                .getAssignedDriverId()).isEqualTo("andres");
    }

    @Test
    void aCompanyCannotReadTheProofOfDeliveryOfAnotherCompanysLoad() {
        FreightLoad foreign = FreightLoad.builder().id(12L).status(LoadStatus.DELIVERED).assignedDriverId("jorge").build();
        when(repository.findById(12L)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.companyProofOfDelivery(12L, "c-andinos"))
                .isInstanceOf(AssignmentAccessDeniedException.class)
                .hasMessageContaining("no es de la flota de su empresa");
    }
}
