package escuelaing.edu.co.truckdar.load_allocation.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "freight_loads", indexes = {
        @Index(name = "idx_load_status", columnList = "status"),
        @Index(name = "idx_generator_id", columnList = "generator_company_id")
})
public class FreightLoad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "generator_company_id", nullable = false)
    private String generatorCompanyId;

    @Column(name = "generator_company_name", nullable = false)
    private String generatorCompanyName;

    @Column(name = "cargo_description", nullable = false)
    private String cargoDescription;

    @Column(name = "weight_tons", nullable = false)
    private Double weightTons;

    @Column(name = "volume_m3")
    private Double volumeM3;

    @Enumerated(EnumType.STRING)
    @Column(name = "required_truck_type", nullable = false)
    private TruckTypeRequired requiredTruckType;

    @Column(name = "origin_city", nullable = false)
    private String originCity;

    @Column(name = "origin_department", nullable = false)
    private String originDepartment;

    @Column(name = "origin_latitude", nullable = false)
    private Double originLatitude;

    @Column(name = "origin_longitude", nullable = false)
    private Double originLongitude;

    @Column(name = "destination_city", nullable = false)
    private String destinationCity;

    @Column(name = "destination_department", nullable = false)
    private String destinationDepartment;

    @Column(name = "destination_latitude", nullable = false)
    private Double destinationLatitude;

    @Column(name = "destination_longitude", nullable = false)
    private Double destinationLongitude;

    @Column(name = "offered_budget", nullable = false)
    private BigDecimal offeredBudget;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private LoadStatus status = LoadStatus.AVAILABLE;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_status")
    private AssignmentStatus assignmentStatus;

    @Column(name = "assigned_driver_id")
    private String assignedDriverId;

    @Column(name = "assigned_driver_name")
    private String assignedDriverName;

    @Column(name = "assigned_vehicle_plate")
    private String assignedVehiclePlate;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "assignment_status_changed_at")
    private LocalDateTime assignmentStatusChangedAt;
}