package escuelaing.edu.co.truckcar.load_allocation.exception;

import lombok.Getter;

import java.util.List;

@Getter
public class RouteIncompatibleException extends InvalidLoadOperationException {

    private final List<String> blockingReasons;

    public RouteIncompatibleException(String message, List<String> blockingReasons) {
        super(message);
        this.blockingReasons = blockingReasons != null ? List.copyOf(blockingReasons) : List.of();
    }
}

