package escuelaing.edu.co.truckcar.load_allocation.exception;

public class RoutingServiceUnavailableException extends InvalidLoadOperationException {

    public RoutingServiceUnavailableException(String message) {
        super(message);
    }

    public RoutingServiceUnavailableException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}

