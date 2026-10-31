package escuelaing.edu.co.truckcar.load_allocation.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Clientes S3: uno interno para leer y escribir objetos y un firmante para las URLs que usa el navegador. Funciona
 * con Amazon S3 (sin endpoint) y con un almacenamiento compatible con S3 en Docker (RustFS).
 */
@Configuration
public class ObjectStorageConfig {

    @Bean(destroyMethod = "close")
    public S3Client s3Client(S3StorageProperties properties) {
        var builder = S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(properties.isPathStyle()).build());
        if (StringUtils.hasText(properties.getEndpoint())) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        return builder.build();
    }

    /** Firma con la dirección pública: la firma S3 incluye el host al que el navegador enviará la petición. */
    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(S3StorageProperties properties) {
        var builder = S3Presigner.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(properties.isPathStyle()).build());
        String endpoint = StringUtils.hasText(properties.getPublicEndpoint())
                ? properties.getPublicEndpoint() : properties.getEndpoint();
        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    private static AwsCredentialsProvider credentials(S3StorageProperties properties) {
        if (StringUtils.hasText(properties.getAccessKey()) && StringUtils.hasText(properties.getSecretKey())) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey()));
        }
        // Amazon S3 en la nube: rol de la instancia o variables AWS_* estándar.
        return DefaultCredentialsProvider.create();
    }
}
