package escuelaing.edu.co.truckcar.load_allocation.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Almacenamiento S3 de las evidencias de entrega (truckcar.storage.s3 en application.yml). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "truckcar.storage.s3")
public class S3StorageProperties {

    /** Dirección interna de la API S3 (HeadObject, PutObject). Vacía: Amazon S3 de la región. */
    private String endpoint;
    /** Base con la que se firman las URLs que usa el navegador (en Docker, el nginx del frontend). Vacía: endpoint. */
    private String publicEndpoint;
    private String region = "us-east-1";
    private String bucket = "pod-media";
    private String accessKey;
    private String secretKey;
    private boolean pathStyle = true;
    private Duration uploadUrlTtl = Duration.ofMinutes(10);
    private Duration downloadUrlTtl = Duration.ofMinutes(10);
    private long maxPhotoBytes = 5L * 1024 * 1024;
    private long maxSignatureBytes = 512L * 1024;
}
