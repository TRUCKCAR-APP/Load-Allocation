package escuelaing.edu.co.truckcar.load_allocation.dto.request;

import jakarta.validation.constraints.NotBlank;
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
    private String driverId;

    @NotBlank(message = "El nombre del conductor es obligatorio")
    private String driverName;

    @NotBlank(message = "La placa del vehículo es obligatoria")
    private String vehiclePlate;
}