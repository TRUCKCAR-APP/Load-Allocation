package escuelaing.edu.co.truckdar.load_allocation.repository;

import escuelaing.edu.co.truckdar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckdar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckdar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckdar.load_allocation.model.TruckTypeRequired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FreightLoadRepository extends JpaRepository<FreightLoad, Long> {

    List<FreightLoad> findByStatusOrderByPublishedAtDesc(LoadStatus status);

    List<FreightLoad> findByStatusAndRequiredTruckType(LoadStatus status, TruckTypeRequired truckType);

    List<FreightLoad> findByGeneratorCompanyIdOrderByPublishedAtDesc(String generatorCompanyId);

    List<FreightLoad> findByAssignedDriverIdOrderByAssignedAtDesc(String assignedDriverId);

    List<FreightLoad> findByAssignmentStatusAndAssignmentStatusChangedAtBefore(AssignmentStatus assignmentStatus,
                                                                              LocalDateTime changedBefore);
}