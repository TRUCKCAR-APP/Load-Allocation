package escuelaing.edu.co.truckdar.load_allocation.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

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

    // ---- Ciclo de vida del viaje (IN_TRANSIT, DELIVERED con prueba de entrega, CANCELLED)
    @Column(name = "trip_started_at")
    private LocalDateTime tripStartedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "pod_receiver_name", length = 150)
    private String podReceiverName;

    @Column(name = "pod_receiver_document", length = 40)
    private String podReceiverDocument;

    @Column(name = "pod_notes", length = 500)
    private String podNotes;

    /** La firma y la foto están en freight_load_pod_media; aquí solo se indica si existen. */
    @Column(name = "pod_has_signature")
    private Boolean podHasSignature;

    @Column(name = "pod_has_photo")
    private Boolean podHasPhoto;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "cancelled_by")
    private String cancelledBy;

    /** Correlación distribuida de la carga: se fija al publicarla y viaja en todos sus eventos. */
    @Column(name = "correlation_id")
    private UUID correlationId;

    /** Propuestas de conductor realizadas para esta carga (inicial + reasignaciones). */
    @Builder.Default
    @Column(name = "assignment_attempts", nullable = false, columnDefinition = "integer default 0")
    private Integer assignmentAttempts = 0;

    /** Conductores que rechazaron o dejaron vencer la propuesta; no se les vuelve a proponer la carga. */
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "freight_load_discarded_drivers", joinColumns = @JoinColumn(name = "load_id"))
    @Column(name = "driver_id", nullable = false)
    private Set<String> discardedDriverIds = new LinkedHashSet<>();

    /** Bloqueo optimista: evita que el scheduler de TIMEOUT y un accept/reject concurrente pisen el estado. */
    @Version
    @Column(name = "version", columnDefinition = "bigint default 0")
    private Long version;
}