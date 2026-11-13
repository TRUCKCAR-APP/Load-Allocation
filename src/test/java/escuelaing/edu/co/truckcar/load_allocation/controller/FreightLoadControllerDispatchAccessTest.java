package escuelaing.edu.co.truckcar.load_allocation.controller;

import escuelaing.edu.co.truckcar.load_allocation.dto.response.FreightLoadResponse;
import escuelaing.edu.co.truckcar.load_allocation.exception.GlobalExceptionHandler;
import escuelaing.edu.co.truckcar.load_allocation.service.IFreightLoadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asignar, notificar y vencer propuestas: solo COORDINADOR, ADMIN y el motor de asignación (SYSTEM). La ocupación de
 * los conductores y la bolsa de cargas disponibles: además, las llamadas internas sin identidad (KAN-43).
 */
class FreightLoadControllerDispatchAccessTest {

    private static final String ASSIGN_BODY =
            "{\"driverId\":\"driver-1\",\"driverName\":\"Ana\",\"vehiclePlate\":\"TDK-410\"}";

    private IFreightLoadService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(IFreightLoadService.class);
        FreightLoadResponse ok = FreightLoadResponse.builder().build();
        when(service.assignDriver(anyLong(), any())).thenReturn(ok);
        when(service.notifyAssignment(anyLong())).thenReturn(ok);
        when(service.timeoutAssignment(anyLong())).thenReturn(ok);
        when(service.getDriverCommitments(any())).thenReturn(java.util.List.of());
        when(service.getAvailableLoads(any(), any())).thenReturn(java.util.List.of());
        mvc = MockMvcBuilders.standaloneSetup(new FreightLoadController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static MockHttpServletRequestBuilder assign() {
        return patch("/api/v1/loads/7/assign").contentType(MediaType.APPLICATION_JSON).content(ASSIGN_BODY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COORDINADOR", "ADMIN", "SYSTEM"})
    void dispatchersCanOperateTheAssignmentCycle(String role) throws Exception {
        mvc.perform(assign().header("X-Auth-Role", role)).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/loads/7/assignment/notify").header("X-Auth-Role", role)).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/loads/7/assignment/timeout").header("X-Auth-Role", role)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONDUCTOR", "GENERADOR"})
    void driversAndGeneratorsGetForbidden(String role) throws Exception {
        mvc.perform(assign().header("X-Auth-User", "driver-1").header("X-Auth-Role", role))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(patch("/api/v1/loads/7/assignment/notify").header("X-Auth-Role", role))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/loads/7/assignment/timeout").header("X-Auth-Role", role))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void missingRoleIsUnauthenticated() throws Exception {
        mvc.perform(assign()).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONDUCTOR", "GENERADOR"})
    void driversAndGeneratorsCannotSeeWhoIsBusyNorTheLoadBoard(String role) throws Exception {
        mvc.perform(get("/api/v1/loads/driver-commitments").header("X-Auth-User", "user-1").header("X-Auth-Role", role))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(get("/api/v1/loads/available").header("X-Auth-User", "user-1").header("X-Auth-Role", role))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COORDINADOR", "ADMIN", "SYSTEM"})
    void controlTowerAndEngineSeeWhoIsBusyAndTheLoadBoard(String role) throws Exception {
        mvc.perform(get("/api/v1/loads/driver-commitments").header("X-Auth-User", "user-1").header("X-Auth-Role", role))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/loads/available").header("X-Auth-User", "user-1").header("X-Auth-Role", role))
                .andExpect(status().isOk());
    }

    /** AI-fleet y el reintento programado llaman por la red interna; por el gateway estas rutas siempre traen X-Auth-User. */
    @Test
    void internalCallsWithoutIdentityStillWork() throws Exception {
        mvc.perform(get("/api/v1/loads/driver-commitments")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/loads/available")).andExpect(status().isOk());
    }

    /** Un rol vacío con usuario no cuenta como llamada interna. */
    @Test
    void aUserWithoutRoleIsNotTreatedAsInternal() throws Exception {
        mvc.perform(get("/api/v1/loads/driver-commitments").header("X-Auth-User", "user-1"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
