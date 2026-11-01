package escuelaing.edu.co.truckcar.load_allocation.tracking;

/** El enlace público no es válido, venció o el envío ya no se puede seguir (410 Gone). */
public class TrackingLinkUnavailableException extends RuntimeException {
    public TrackingLinkUnavailableException(String message) {
        super(message);
    }
}
