package escuelaing.edu.co.truckcar.load_allocation.service;

import escuelaing.edu.co.truckcar.load_allocation.model.AssignmentStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.FreightLoad;
import escuelaing.edu.co.truckcar.load_allocation.repository.FreightLoadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Vence automáticamente las propuestas NOTIFIED sin respuesta dentro de la ventana configurada,
 * emitiendo assignment.timeout para que el motor de asignación busque otro candidato.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssignmentTimeoutScheduler {

    private final FreightLoadRepository repository;
    private final IFreightLoadService freightLoadService;

    @Value("${truckcar.assignment.response-timeout-minutes:5}")
    private long responseTimeoutMinutes = 5;

    @Scheduled(fixedDelayString = "${truckcar.assignment.timeout-check-interval-ms:30000}")
    public void expireUnansweredAssignments() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(responseTimeoutMinutes);
        List<FreightLoad> expired = repository
                .findByAssignmentStatusAndAssignmentStatusChangedAtBefore(AssignmentStatus.NOTIFIED, cutoff);
        for (FreightLoad load : expired) {
            try {
                freightLoadService.expireIfUnanswered(load.getId(), cutoff);
            } catch (ObjectOptimisticLockingFailureException concurrentChange) {
                log.debug("La carga #{} cambió de estado mientras vencía; se omite", load.getId());
            } catch (RuntimeException exception) {
                log.error("No se pudo vencer la propuesta de la carga #{}", load.getId(), exception);
            }
        }
    }
}
