package escuelaing.edu.co.truckdar.load_allocation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Prueba de entrega (POD) que registra el conductor al finalizar el viaje. */
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
}
