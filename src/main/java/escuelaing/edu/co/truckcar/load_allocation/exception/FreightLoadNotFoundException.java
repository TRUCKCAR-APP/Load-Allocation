package escuelaing.edu.co.truckcar.load_allocation.exception;

public class FreightLoadNotFoundException extends RuntimeException {
    public FreightLoadNotFoundException(String message) {
        super(message);
    }
}