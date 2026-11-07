package escuelaing.edu.co.truckcar.load_allocation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignDriverRequest {

    @NotBlank(message = "El ID del conductor asignado es obligatorio")
    @Size(max = 255, message = "El ID del conductor no puede superar 255 caracteres")
    private String driverId;

    @NotBlank(message = "El nombre del conductor es obligatorio")
    @Size(max = 255, message = "El nombre del conductor no puede superar 255 caracteres")
    private String driverName;

    @NotBlank(message = "La placa del vehículo es obligatoria")
    @Size(max = 255, message = "La placa del vehículo no puede superar 255 caracteres")
    private String vehiclePlate;
}