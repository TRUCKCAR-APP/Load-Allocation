package escuelaing.edu.co.truckcar.load_allocation.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Último estado conocido de la cuenta de un conductor en user-identity (lo mantiene UserAccountEventListener). */
@Entity
@Table(name = "driver_account_status")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DriverAccountStatus {

    public static final String VERIFIED = "ACTIVE";

    @Id
    @Column(name = "driver_id", nullable = false, length = 255)
    private String driverId;

    @Column(name = "status", nullable = false, length = 40)
    private String status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
