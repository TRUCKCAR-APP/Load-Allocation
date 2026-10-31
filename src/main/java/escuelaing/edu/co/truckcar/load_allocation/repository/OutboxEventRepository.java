package escuelaing.edu.co.truckcar.load_allocation.repository;

import escuelaing.edu.co.truckcar.load_allocation.model.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findByPublishedAtIsNullOrderByCreatedAtAsc(Pageable pageable);

    /**
     * Candado de PostgreSQL de la transacción actual (se libera al confirmarla): con varias réplicas solo una publica
     * el outbox a la vez, sin eventos duplicados y conservando el orden por carga.
     */
    @Query(value = "select pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryAcquirePublisherLock(@Param("key") long key);

    @Modifying
    @Transactional
    @Query("""
            update OutboxEvent event
               set event.publishedAt = :publishedAt,
                   event.attempts = event.attempts + 1,
                   event.lastError = null
             where event.id = :id
               and event.publishedAt is null
            """)
    int markPublished(@Param("id") UUID id, @Param("publishedAt") Instant publishedAt);

    @Modifying
    @Transactional
    @Query("""
            update OutboxEvent event
               set event.attempts = event.attempts + 1,
                   event.lastError = :lastError
             where event.id = :id
               and event.publishedAt is null
            """)
    int markFailed(@Param("id") UUID id, @Param("lastError") String lastError);
}
