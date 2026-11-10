package escuelaing.edu.co.truckcar.load_allocation.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Prueba de entrega (POD) que registra el conductor al finalizar el viaje. La firma y la foto se suben antes al
 * almacenamiento con URLs prefirmadas (POST /{loadId}/proof-of-delivery/upload-urls); aquí solo viajan sus
 * referencias y hashes, nunca la imagen en Base64.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryConfirmationRequest {

    @NotBlank(message = "El nombre de quien recibe la carga es obligatorio")
    @Size(max = 150, message = "El nombre de quien recibe no puede superar 150 caracteres")
    private String receiverName;

    @Size(max = 40, message = "El documento de quien recibe no puede superar 40 caracteres")
    private String receiverDocument;

    @Size(max = 500, message = "Las observaciones no pueden superar 500 caracteres")
    private String notes;

    /**
     * Firma de quien recibe: obligatoria. Es la evidencia digital mínima de la entrega (la PWA ya la exige); sin ella
     * la carga quedaba DELIVERED sin ninguna prueba asociada.
     */
    @NotNull(message = "La firma de quien recibe es obligatoria para registrar la entrega")
    @Valid
    private PodObjectReference signature;

    /** Foto de la carga entregada (opcional). */
    @Valid
    private PodObjectReference photo;
}
