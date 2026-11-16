package escuelaing.edu.co.truckcar.load_allocation.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato de errores RFC 7807: los campos del estándar más los de antes, para que la PWA (lee message y details y
 * decide por Content-Type application/json) y AI-fleet (lee message de los 409) sigan funcionando igual.
 */
class ProblemDetailContractTest {

    @RestController
    static class ProbeController {
        record Body(@NotBlank(message = "La ciudad es obligatoria") String city,
                    @Positive(message = "El peso debe ser mayor que 0") Double weight) { }

        @GetMapping("/loads/conflict")
        String conflict() {
            throw new InvalidLoadOperationException("La carga #7 no admite una nueva asignación");
        }

        @GetMapping("/loads/forbidden")
        String forbidden() {
            throw new AssignmentAccessDeniedException("No tiene acceso a la carga #7", false);
        }

        @PostMapping("/loads/validate")
        String validate(@Valid @RequestBody Body body) {
            return "ok";
        }

        @GetMapping("/loads/boom")
        String boom() {
            throw new IllegalStateException("detalle interno que no debe salir");
        }
    }

    /** Con el ObjectMapper de la aplicación (JacksonConfig), no el de MockMvc: así se probó mal la primera vez. */
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                    new escuelaing.edu.co.truckcar.load_allocation.config.JacksonConfig().objectMapper()))
            .build();

    @Test
    void aConflictHasTheStandardFieldsAndKeepsTheOldOnes() throws Exception {
        mvc.perform(get("/loads/conflict").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://truckcar.co/errors/invalid-load-operation"))
                .andExpect(jsonPath("$.title").value("Operación inválida sobre la carga"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("La carga #7 no admite una nueva asignación"))
                .andExpect(jsonPath("$.instance").value("/loads/conflict"))
                .andExpect(jsonPath("$.message").value("La carga #7 no admite una nueva asignación"))
                .andExpect(jsonPath("$.error").value("Operación inválida sobre la carga"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /** La PWA envía Accept: application/json y solo interpreta el cuerpo si el Content-Type lo contiene. */
    @Test
    void aClientThatAsksForJsonGetsJsonAndOneThatAsksForProblemJsonGetsIt() throws Exception {
        mvc.perform(get("/loads/forbidden").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mvc.perform(get("/loads/forbidden").accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
        // Sin Accept (otros servicios, curl): JSON normal.
        mvc.perform(get("/loads/forbidden"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("No tiene acceso a la carga #7"));
    }

    @Test
    void aValidationErrorListsEachFieldInDetailsAsBefore() throws Exception {
        mvc.perform(post("/loads/validate").accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\" \",\"weight\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://truckcar.co/errors/validation-error"))
                .andExpect(jsonPath("$.details.city").value("La ciudad es obligatoria"))
                .andExpect(jsonPath("$.details.weight").value("El peso debe ser mayor que 0"))
                .andExpect(jsonPath("$.message").value("La ciudad es obligatoria; El peso debe ser mayor que 0"))
                .andExpect(jsonPath("$.detail").value("La ciudad es obligatoria; El peso debe ser mayor que 0"));
    }

    @Test
    void anUnexpectedErrorDoesNotLeakInternals() throws Exception {
        mvc.perform(get("/loads/boom").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://truckcar.co/errors/internal-error"))
                .andExpect(jsonPath("$.detail").value("No se pudo procesar la solicitud de la carga"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("detalle interno"))));
    }
}
