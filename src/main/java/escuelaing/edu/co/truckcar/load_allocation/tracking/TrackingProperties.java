package escuelaing.edu.co.truckcar.load_allocation.tracking;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Enlaces públicos de seguimiento (truckcar.tracking en application.yml). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "truckcar.tracking")
public class TrackingProperties {

    /** Clave HMAC-SHA256 de los tokens (>= 32 bytes). Vacía: se genera una por proceso y los enlaces no sobreviven reinicios. */
    private String secret;
    /** URL de la PWA a la que apunta el enlace (?track=<token>). */
    private String publicBaseUrl = "http://localhost:5500/";
    /** Vigencia máxima de un enlace. */
    private Duration maxTtl = Duration.ofHours(48);
    /** Tras la entrega el enlace sigue mostrando el resultado este tiempo y luego vence. */
    private Duration afterDelivery = Duration.ofHours(6);
    /** fleets-drivers: última posición del vehículo en ruta. */
    private String fleetsDriversUrl = "http://localhost:8081";
}
