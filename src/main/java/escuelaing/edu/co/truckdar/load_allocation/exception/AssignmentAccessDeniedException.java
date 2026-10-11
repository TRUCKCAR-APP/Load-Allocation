package escuelaing.edu.co.truckdar.load_allocation.exception;

/**
 * El usuario autenticado no puede operar la asignación: 401 si no hay identidad, 403 si no es el conductor asignado.
 */
public class AssignmentAccessDeniedException extends RuntimeException {

    private final boolean unauthenticated;

    public AssignmentAccessDeniedException(String message, boolean unauthenticated) {
        super(message);
        this.unauthenticated = unauthenticated;
    }

    public boolean isUnauthenticated() {
        return unauthenticated;
    }
}
