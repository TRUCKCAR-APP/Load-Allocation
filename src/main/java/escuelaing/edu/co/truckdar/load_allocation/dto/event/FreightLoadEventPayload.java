package escuelaing.edu.co.truckdar.load_allocation.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import escuelaing.edu.co.truckdar.load_allocation.model.AssignmentStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreightLoadEventPayload {

    private Long loadId;
    private String generatorCompanyId;
    private String cargoDescription;
    private Double weightTons;
    private String requiredTruckType;
    private String originCity;
    private String destinationCity;
    private BigDecimal offeredBudget;
    private String loadStatus;
    private AssignmentStatus assignmentStatus;
    private String assignedDriverId;
    private String assignedDriverName;
    private String assignedVehiclePlate;
}
