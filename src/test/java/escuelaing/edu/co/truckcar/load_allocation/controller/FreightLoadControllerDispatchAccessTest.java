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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Asignar, notificar y vencer propuestas: solo COORDINADOR, ADMIN y el motor de asignación (SYSTEM). */
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
}
