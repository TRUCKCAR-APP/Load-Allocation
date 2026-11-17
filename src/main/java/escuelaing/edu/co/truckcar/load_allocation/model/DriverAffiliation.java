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

/**
 * Última afiliación conocida de un conductor en user-identity: INDEPENDIENTE (sin cargas) o EMPRESA (con cargas solo
 * si la afiliación está ACTIVA). La mantiene UserAccountEventListener con user.affiliation.changed.
 */
@Entity
@Table(name = "driver_affiliation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DriverAffiliation {

    @Id
    @Column(name = "driver_id", nullable = false, length = 255)
    private String driverId;

    @Column(name = "driver_type", nullable = false, length = 20)
    private String driverType;

    @Column(name = "company_id", length = 64)
    private String companyId;

    @Column(name = "company_name", length = 150)
    private String companyName;

    @Column(name = "affiliation_status", length = 20)
    private String affiliationStatus;

    @Column(name = "load_eligible", nullable = false)
    private boolean loadEligible;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
