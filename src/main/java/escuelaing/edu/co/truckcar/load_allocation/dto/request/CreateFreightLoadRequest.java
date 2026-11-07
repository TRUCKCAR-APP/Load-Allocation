package escuelaing.edu.co.truckcar.load_allocation.dto.request;

import escuelaing.edu.co.truckcar.load_allocation.model.TruckTypeRequired;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Los límites de texto coinciden con las columnas VARCHAR(255) de V1__load_allocation_schema.sql y los de
 * coordenadas con el rango WGS84: sin ellos un valor fuera de rango llegaba a la base y respondía 500.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateFreightLoadRequest {

    /**
     * Solo para llamadas internas sin gateway: con usuario autenticado, la empresa generadora es siempre
     * X-Auth-User y este valor se ignora.
     */
    @Size(max = 255, message = "El ID de la empresa generadora no puede superar 255 caracteres")
    private String generatorCompanyId;

    @NotBlank(message = "El nombre de la empresa generadora es obligatorio")
    @Size(max = 255, message = "El nombre de la empresa generadora no puede superar 255 caracteres")
    private String generatorCompanyName;

    @NotBlank(message = "La descripción de la carga es obligatoria")
    @Size(max = 255, message = "La descripción de la carga no puede superar 255 caracteres")
    private String cargoDescription;

    @NotNull(message = "El peso en toneladas es obligatorio")
    @DecimalMin(value = "0.1", message = "El peso debe ser mayor a 0 toneladas")
    private Double weightTons;

    @Positive(message = "El volumen debe ser mayor a 0 m³")
    private Double volumeM3;

    @NotNull(message = "El tipo de camión requerido es obligatorio")
    private TruckTypeRequired requiredTruckType;

    @NotBlank(message = "La ciudad de origen es obligatoria")
    @Size(max = 255, message = "La ciudad de origen no puede superar 255 caracteres")
    private String originCity;

    @NotBlank(message = "El departamento de origen es obligatorio")
    @Size(max = 255, message = "El departamento de origen no puede superar 255 caracteres")
    private String originDepartment;

    @NotNull(message = "La latitud de origen es obligatoria")
    @DecimalMin(value = "-90", message = "La latitud de origen debe estar entre -90 y 90")
    @DecimalMax(value = "90", message = "La latitud de origen debe estar entre -90 y 90")
    private Double originLatitude;

    @NotNull(message = "La longitud de origen es obligatoria")
    @DecimalMin(value = "-180", message = "La longitud de origen debe estar entre -180 y 180")
    @DecimalMax(value = "180", message = "La longitud de origen debe estar entre -180 y 180")
    private Double originLongitude;

    @NotBlank(message = "La ciudad de destino es obligatoria")
    @Size(max = 255, message = "La ciudad de destino no puede superar 255 caracteres")
    private String destinationCity;

    @NotBlank(message = "El departamento de destino es obligatorio")
    @Size(max = 255, message = "El departamento de destino no puede superar 255 caracteres")
    private String destinationDepartment;

    @NotNull(message = "La latitud de destino es obligatoria")
    @DecimalMin(value = "-90", message = "La latitud de destino debe estar entre -90 y 90")
    @DecimalMax(value = "90", message = "La latitud de destino debe estar entre -90 y 90")
    private Double destinationLatitude;

    @NotNull(message = "La longitud de destino es obligatoria")
    @DecimalMin(value = "-180", message = "La longitud de destino debe estar entre -180 y 180")
    @DecimalMax(value = "180", message = "La longitud de destino debe estar entre -180 y 180")
    private Double destinationLongitude;

    @NotNull(message = "El presupuesto ofertado es obligatorio")
    @DecimalMin(value = "1000", message = "El presupuesto mínimo debe ser 1000")
    private BigDecimal offeredBudget;
}
