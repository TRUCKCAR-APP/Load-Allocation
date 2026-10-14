package escuelaing.edu.co.truckcar.load_allocation.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import escuelaing.edu.co.truckcar.load_allocation.model.AssignmentStatus;

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
    private Integer assignmentAttempts;
    private List<String> excludedDriverIds;
}
