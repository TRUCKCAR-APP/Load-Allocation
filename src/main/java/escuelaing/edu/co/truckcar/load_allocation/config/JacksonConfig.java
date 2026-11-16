package escuelaing.edu.co.truckcar.load_allocation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;

@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // Lector tolerante: un campo nuevo en un evento de otro servicio (o en un cuerpo) se ignora en lugar de mandar el
        // mensaje a la cola de muertos. Es lo que Spring Boot hace con su ObjectMapper; este es propio y no lo traía.
        mapper.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // Un ObjectMapper propio no trae el mixin que Spring registra en el suyo: sin él, las propiedades adicionales de
        // un ProblemDetail (message, error, details...) saldrían anidadas en "properties" en lugar de en la raíz.
        mapper.addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
        return mapper;
    }
}