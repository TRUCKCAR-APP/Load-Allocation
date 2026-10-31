package escuelaing.edu.co.truckcar.load_allocation.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Operaciones S3 de las evidencias de entrega. Los archivos nunca pasan por los microservicios: la PWA los sube y
 * descarga con URLs prefirmadas de vida corta, y aquí solo se verifica que el objeto exista con el hash declarado.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PodObjectStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final S3StorageProperties properties;

    /** URL de subida (PUT) firmada para un tipo, tamaño y SHA-256 exactos: S3 rechaza un archivo distinto. */
    public PresignedUpload presignUpload(String objectKey, String contentType, long sizeBytes, String sha256Hex) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .contentType(contentType)
                .contentLength(sizeBytes)
                .checksumSHA256(base64(sha256Hex))
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(properties.getUploadUrlTtl())
                .putObjectRequest(put)
                .build());
        Map<String, String> headers = new LinkedHashMap<>();
        presigned.signedHeaders().forEach((name, values) -> {
            // host y content-length los fija el navegador; el resto debe enviarse tal cual se firmó.
            if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length")) {
                headers.put(name, String.join(",", values));
            }
        });
        return new PresignedUpload(presigned.url().toString(), headers, presigned.expiration());
    }

    public String presignDownload(String objectKey) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(properties.getDownloadUrlTtl())
                .getObjectRequest(GetObjectRequest.builder().bucket(properties.getBucket()).key(objectKey).build())
                .build()).url().toString();
    }

    /** Metadatos del objeto subido, o vacío si no existe. */
    public Optional<StoredObject> head(String objectKey) {
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .checksumMode(ChecksumMode.ENABLED)
                    .build());
            return Optional.of(new StoredObject(head.contentLength(), head.contentType(), head.checksumSHA256()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** SHA-256 (hex) del contenido almacenado, calculado leyendo el objeto. */
    public String sha256Of(String objectKey) {
        try (InputStream in = s3.getObject(GetObjectRequest.builder()
                .bucket(properties.getBucket()).key(objectKey).build())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.io.IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("No se pudo leer " + objectKey + " para verificar su hash", e);
        }
    }

    public void put(String objectKey, byte[] content, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(objectKey)
                        .contentType(contentType)
                        .checksumSHA256(base64(sha256Hex(content)))
                        .build(),
                RequestBody.fromBytes(content));
    }

    public void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(properties.getBucket()).build());
        } catch (NoSuchBucketException e) {
            createBucket();
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            createBucket();
        }
    }

    private void createBucket() {
        s3.createBucket(CreateBucketRequest.builder().bucket(properties.getBucket()).build());
        log.info("Bucket {} creado para las evidencias de entrega", properties.getBucket());
    }

    public static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String base64(String sha256Hex) {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex));
    }

    /** headers: cabeceras firmadas que el navegador debe enviar en el PUT (Content-Type, x-amz-checksum-sha256). */
    public record PresignedUpload(String url, Map<String, String> headers, Instant expiresAt) {
    }

    /** checksumSha256 en Base64 si el almacenamiento lo devuelve (subidas con checksum); puede ser null. */
    public record StoredObject(Long sizeBytes, String contentType, String checksumSha256) {
    }
}
