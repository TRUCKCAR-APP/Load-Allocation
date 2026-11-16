package escuelaing.edu.co.truckcar.load_allocation.service;

import escuelaing.edu.co.truckcar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckcar.load_allocation.model.DriverAccountStatus;
import escuelaing.edu.co.truckcar.load_allocation.repository.DriverAccountStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Solo una cuenta verificada por un administrador (ACTIVE en user-identity) puede aceptar cargas. Un conductor en
 * PENDING_VERIFICATION, SUSPENDED o del que aún no llegó ningún evento no acepta: se falla cerrado (Lote 2).
 */
@Component
@RequiredArgsConstructor
public class DriverAccountGuard {

    private final DriverAccountStatusRepository accounts;

    public void requireVerified(String driverId) {
        String status = accounts.findById(driverId).map(DriverAccountStatus::getStatus).orElse(null);
        if (DriverAccountStatus.VERIFIED.equals(status)) {
            return;
        }
        String why;
        if (status == null) {
            why = "no se pudo confirmar que su cuenta esté verificada";
        } else if ("PENDING_VERIFICATION".equals(status)) {
            why = "su cuenta aún no ha sido verificada por un administrador";
        } else if ("SUSPENDED".equals(status)) {
            why = "su cuenta está suspendida";
        } else {
            why = "su cuenta está en estado " + status;
        }
        throw new AssignmentAccessDeniedException("No puede aceptar cargas: " + why
                + ". Comuníquese con la torre de control.", false);
    }
}
