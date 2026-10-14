package escuelaing.edu.co.truckcar.load_allocation.dto.response;

import escuelaing.edu.co.truckcar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.TruckTypeRequired;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreightLoadResponse {
    private Long id;
    private String generatorCompanyId;
    private String generatorCompanyName;
    private String cargoDescription;
    private Double weightTons;
    private Double volumeM3;
    private TruckTypeRequired requiredTruckType;
    private String originCity;
    private String originDepartment;
    private Double originLatitude;
    private Double originLongitude;
    private String destinationCity;
    private String destinationDepartment;
    private Double destinationLatitude;
    private Double destinationLongitude;
    private BigDecimal offeredBudget;
    private LoadStatus status;
    private AssignmentStatus assignmentStatus;
    private String assignedDriverId;
    private String assignedDriverName;
    private String assignedVehiclePlate;
    private LocalDateTime publishedAt;
    private LocalDateTime assignedAt;
    private LocalDateTime assignmentStatusChangedAt;
    private UUID correlationId;
    private Integer assignmentAttempts;
    private List<String> discardedDriverIds;
    private LocalDateTime tripStartedAt;
    private LocalDateTime deliveredAt;
    private String podReceiverName;
    private String podReceiverDocument;
    private String podNotes;
    /** La firma y la foto se consultan en GET /loads/{id}/proof-of-delivery. */
    private boolean podHasSignature;
    private boolean podHasPhoto;
    private LocalDateTime cancelledAt;
    private String cancellationReason;
    private String cancelledBy;
}