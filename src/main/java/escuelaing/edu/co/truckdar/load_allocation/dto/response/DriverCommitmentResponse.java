package escuelaing.edu.co.truckdar.load_allocation.dto.response;

import escuelaing.edu.co.truckdar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckdar.load_allocation.model.LoadStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Conductor comprometido con una carga: la aceptó (ASSIGNED), la lleva en ruta (IN_TRANSIT) o tiene
 * una propuesta vigente (AVAILABLE + PROPOSED/NOTIFIED). El motor de asignación no debe proponerle otra.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverCommitmentResponse {

    private String driverId;
    private Long loadId;
    private LoadStatus loadStatus;
    private AssignmentStatus assignmentStatus;
}
