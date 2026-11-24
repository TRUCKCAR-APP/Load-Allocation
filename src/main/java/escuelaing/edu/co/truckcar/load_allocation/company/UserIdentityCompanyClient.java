package escuelaing.edu.co.truckcar.load_allocation.company;

import com.fasterxml.jackson.databind.JsonNode;
import escuelaing.edu.co.truckcar.load_allocation.exception.AssignmentAccessDeniedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Empresa de la cuenta TRANSPORTADORA: se le pregunta a User-Identity con el mismo token del usuario
 * (GET /api/v1/companies/me). User-Identity valida el token y la propiedad, así que una cuenta no puede hacerse pasar
 * por otra empresa.
 */
@Slf4j
@Component
public class UserIdentityCompanyClient {

    public record Company(String id, String name) {
    }

    private final RestClient restClient;

    public UserIdentityCompanyClient(RestClient.Builder builder,
                                     @Value("${truckcar.services.user-identity-url:http://localhost:8080}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    public Company myCompany(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new AssignmentAccessDeniedException("Se requiere la sesión de la transportadora", true);
        }
        JsonNode company;
        try {
            company = restClient.get().uri("/api/v1/companies/me")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new AssignmentAccessDeniedException("Esta cuenta no tiene una empresa transportadora", false);
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new AssignmentAccessDeniedException("La sesión de la transportadora no es válida", true);
        } catch (HttpClientErrorException.Forbidden e) {
            throw new AssignmentAccessDeniedException("Solo una cuenta de transportadora tiene cargas de su flota", false);
        } catch (Exception e) {
            log.warn("No se pudo consultar la empresa en User-Identity: {}", e.getClass().getSimpleName());
            throw new CompanyLookupUnavailableException("No se pudo confirmar su empresa; inténtelo de nuevo en unos segundos");
        }
        if (company == null || !company.hasNonNull("id")) {
            throw new CompanyLookupUnavailableException("User-Identity no devolvió la empresa de la cuenta");
        }
        return new Company(company.get("id").asText(), company.path("name").asText(null));
    }
}
