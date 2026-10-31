package escuelaing.edu.co.truckcar.load_allocation.exception;

/** Una evidencia de entrega referenciada no está en el almacenamiento o no coincide con lo declarado (422). */
public class ProofOfDeliveryEvidenceException extends RuntimeException {

    public ProofOfDeliveryEvidenceException(String message) {
        super(message);
    }
}
