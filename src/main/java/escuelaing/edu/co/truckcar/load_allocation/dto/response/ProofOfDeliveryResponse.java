package escuelaing.edu.co.truckcar.load_allocation.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Prueba de entrega completa de una carga DELIVERED. signatureUrl y photoUrl son URLs prefirmadas de descarga de
 * vida corta (se generan en cada consulta); los hashes permiten verificar la integridad de lo descargado.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProofOfDeliveryResponse {
    private Long loadId;
    private String driverId;
    private String driverName;
    private String vehiclePlate;
    private LocalDateTime tripStartedAt;
    private LocalDateTime deliveredAt;
    private String receiverName;
    private String receiverDocument;
    private String notes;
    private String signatureUrl;
    private String signatureSha256;
    private String photoUrl;
    private String photoSha256;

    /** Claves en el almacenamiento: internas, se convierten en URLs prefirmadas antes de responder. */
    @JsonIgnore
    private String signatureObjectKey;
    @JsonIgnore
    private String photoObjectKey;
}
