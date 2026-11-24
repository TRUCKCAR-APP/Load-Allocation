package escuelaing.edu.co.truckcar.load_allocation.repository;

import escuelaing.edu.co.truckcar.load_allocation.model.DriverAffiliation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverAffiliationRepository extends JpaRepository<DriverAffiliation, String> {

    java.util.List<DriverAffiliation> findByCompanyIdAndAffiliationStatus(String companyId, String affiliationStatus);
}
