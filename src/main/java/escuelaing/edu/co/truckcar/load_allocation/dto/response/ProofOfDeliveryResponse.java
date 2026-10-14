package escuelaing.edu.co.truckcar.load_allocation.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Prueba de entrega completa de una carga DELIVERED, incluidas la firma y la foto. */
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
    private String signatureDataUrl;
    private String photoUrl;
}
