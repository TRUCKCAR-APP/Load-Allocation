package escuelaing.edu.co.truckcar.load_allocation.service;

import escuelaing.edu.co.truckcar.load_allocation.exception.AssignmentAccessDeniedException;
import escuelaing.edu.co.truckcar.load_allocation.model.DriverAccountStatus;
import escuelaing.edu.co.truckcar.load_allocation.model.DriverAffiliation;
import escuelaing.edu.co.truckcar.load_allocation.repository.DriverAccountStatusRepository;
import escuelaing.edu.co.truckcar.load_allocation.repository.DriverAffiliationRepository;
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
    private final DriverAffiliationRepository affiliations;

    public void requireVerified(String driverId) {
        String status = accounts.findById(driverId).map(DriverAccountStatus::getStatus).orElse(null);
        if (DriverAccountStatus.VERIFIED.equals(status)) {
            requireAffiliationAllowsLoads(driverId);
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

    /**
     * Un independiente usa la navegación pero no recibe cargas, y un conductor de empresa las recibe solo con la
     * afiliación ACTIVA. Sin fila (cuentas anteriores a la afiliación) no se restringe nada.
     */
    private void requireAffiliationAllowsLoads(String driverId) {
        DriverAffiliation affiliation = affiliations.findById(driverId).orElse(null);
        if (affiliation == null || affiliation.isLoadEligible()) {
            return;
        }
        String why;
        if ("INDEPENDIENTE".equals(affiliation.getDriverType())) {
            why = "es conductor independiente: usa la navegación y las alertas, pero no recibe cargas."
                    + " Si trabaja con una transportadora, pida la afiliación desde su perfil";
        } else if ("RECHAZADA".equals(affiliation.getAffiliationStatus())) {
            why = "su transportadora rechazó la afiliación";
        } else {
            why = "su transportadora aún no aprueba su afiliación";
        }
        throw new AssignmentAccessDeniedException("No puede aceptar cargas: " + why + ".", false);
    }
}
