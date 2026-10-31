package escuelaing.edu.co.truckcar.load_allocation.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Referencias a la firma y la foto de la prueba de entrega. Las imágenes viven en el almacenamiento de objetos (S3);
 * aquí solo su clave, su SHA-256 (verificado al registrar la entrega), el tipo y el tamaño.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "freight_load_pod_media")
public class ProofOfDeliveryMedia {

    @Id
    @Column(name = "load_id")
    private Long loadId;

    @Column(name = "signature_object_key", length = 512)
    private String signatureObjectKey;

    @Column(name = "signature_sha256", length = 64)
    private String signatureSha256;

    @Column(name = "signature_content_type", length = 100)
    private String signatureContentType;

    @Column(name = "signature_size_bytes")
    private Long signatureSizeBytes;

    @Column(name = "photo_object_key", length = 512)
    private String photoObjectKey;

    @Column(name = "photo_sha256", length = 64)
    private String photoSha256;

    @Column(name = "photo_content_type", length = 100)
    private String photoContentType;

    @Column(name = "photo_size_bytes")
    private Long photoSizeBytes;

    /** Enlace https de una foto registrada antes de usar el almacenamiento de objetos (solo datos migrados). */
    @Column(name = "photo_external_url", length = 2048)
    private String photoExternalUrl;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
