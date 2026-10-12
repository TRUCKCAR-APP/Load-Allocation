package escuelaing.edu.co.truckdar.load_allocation.dto.request;

import escuelaing.edu.co.truckdar.load_allocation.model.TruckTypeRequired;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateFreightLoadRequest {

    /**
     * Solo para llamadas internas sin gateway: con usuario autenticado, la empresa generadora es siempre
     * X-Auth-User y este valor se ignora.
     */
    private String generatorCompanyId;

    @NotBlank(message = "El nombre de la empresa generadora es obligatorio")
    private String generatorCompanyName;

    @NotBlank(message = "La descripción de la carga es obligatoria")
    private String cargoDescription;

    @NotNull(message = "El peso en toneladas es obligatorio")
    @DecimalMin(value = "0.1", message = "El peso debe ser mayor a 0 toneladas")
    private Double weightTons;

    private Double volumeM3;

    @NotNull(message = "El tipo de camión requerido es obligatorio")
    private TruckTypeRequired requiredTruckType;

    @NotBlank(message = "La ciudad de origen es obligatoria")
    private String originCity;

    @NotBlank(message = "El departamento de origen es obligatorio")
    private String originDepartment;

    @NotNull(message = "La latitud de origen es obligatoria")
    private Double originLatitude;

    @NotNull(message = "La longitud de origen es obligatoria")
    private Double originLongitude;

    @NotBlank(message = "La ciudad de destino es obligatoria")
    private String destinationCity;

    @NotBlank(message = "El departamento de destino es obligatorio")
    private String destinationDepartment;

    @NotNull(message = "La latitud de destino es obligatoria")
    private Double destinationLatitude;

    @NotNull(message = "La longitud de destino es obligatoria")
    private Double destinationLongitude;

    @NotNull(message = "El presupuesto ofertado es obligatorio")
    @DecimalMin(value = "1000", message = "El presupuesto mínimo debe ser 1000")
    private BigDecimal offeredBudget;
}