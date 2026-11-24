package escuelaing.edu.co.truckcar.load_allocation.company;

/** User-Identity no respondió al confirmar la empresa de la cuenta: 503, se puede reintentar. */
public class CompanyLookupUnavailableException extends RuntimeException {

    public CompanyLookupUnavailableException(String message) {
        super(message);
    }
}
