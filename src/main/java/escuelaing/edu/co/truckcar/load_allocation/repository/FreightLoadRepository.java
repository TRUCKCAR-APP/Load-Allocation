package escuelaing.edu.co.truckcar.load_allocation.repository;

import escuelaing.edu.co.truckcar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckcar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.TruckTypeRequired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface FreightLoadRepository extends JpaRepository<FreightLoad, Long> {

    List<FreightLoad> findByStatusOrderByPublishedAtDesc(LoadStatus status);

    List<FreightLoad> findByStatusAndRequiredTruckType(LoadStatus status, TruckTypeRequired truckType);

    List<FreightLoad> findByGeneratorCompanyIdOrderByPublishedAtDesc(String generatorCompanyId);

    List<FreightLoad> findByAssignedDriverIdOrderByAssignedAtDesc(String assignedDriverId);

    List<FreightLoad> findByAssignmentStatusAndAssignmentStatusChangedAtBefore(AssignmentStatus assignmentStatus,
                                                                              LocalDateTime changedBefore);

    /** Cargas que ocupan a su conductor: en status comprometido o abiertas con una propuesta vigente. */
    @Query("""
            select l from FreightLoad l
            where l.assignedDriverId is not null
              and (l.status in :committed or (l.status = :open and l.assignmentStatus in :pending))
            """)
    List<FreightLoad> findDriverCommitments(@Param("committed") Collection<LoadStatus> committed,
                                            @Param("open") LoadStatus open,
                                            @Param("pending") Collection<AssignmentStatus> pending);

    List<FreightLoad> findByAssignedDriverIdAndStatusIn(String assignedDriverId, Collection<LoadStatus> statuses);

    List<FreightLoad> findTop200ByStatusInOrderByPublishedAtDesc(Collection<LoadStatus> statuses);

    List<FreightLoad> findTop200ByOrderByPublishedAtDesc();

    /** Cargas de la flota de una transportadora: las propias y las que llevan (o llevaron) sus conductores. */
    @Query("SELECT l FROM FreightLoad l WHERE l.ownerCompanyId = :companyId OR l.assignedDriverId IN :drivers"
            + " ORDER BY l.publishedAt DESC")
    List<FreightLoad> findCompanyFleetLoads(@Param("companyId") String companyId, @Param("drivers") Collection<String> drivers,
                                           org.springframework.data.domain.Pageable page);
}