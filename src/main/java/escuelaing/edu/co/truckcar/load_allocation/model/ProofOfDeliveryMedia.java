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
 * Firma y foto de la prueba de entrega. Viven fuera de freight_loads para que los listados de cargas no
 * arrastren imágenes de varios cientos de KB por cada carga entregada.
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

    @Column(name = "signature_data_url", columnDefinition = "TEXT")
    private String signatureDataUrl;

    @Column(name = "photo_url", columnDefinition = "TEXT")
    private String photoUrl;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
