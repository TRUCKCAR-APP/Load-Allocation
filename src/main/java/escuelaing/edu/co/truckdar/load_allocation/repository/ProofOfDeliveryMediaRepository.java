package escuelaing.edu.co.truckdar.load_allocation.repository;

import escuelaing.edu.co.truckdar.load_allocation.model.ProofOfDeliveryMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProofOfDeliveryMediaRepository extends JpaRepository<ProofOfDeliveryMedia, Long> {
}
