package escuelaing.edu.co.truckcar.load_allocation.repository;

import escuelaing.edu.co.truckcar.load_allocation.model.DriverAccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverAccountStatusRepository extends JpaRepository<DriverAccountStatus, String> {
}
