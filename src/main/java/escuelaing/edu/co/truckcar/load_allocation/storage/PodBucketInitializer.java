package escuelaing.edu.co.truckcar.load_allocation.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Crea el bucket de evidencias si no existe. No detiene el arranque: sin almacenamiento solo fallan las subidas de
 * POD (la PWA las conserva en su cola offline y las reintenta).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PodBucketInitializer implements ApplicationRunner {

    private final PodObjectStorage storage;

    @Override
    public void run(ApplicationArguments args) {
        try {
            storage.ensureBucket();
        } catch (RuntimeException e) {
            log.warn("No se pudo verificar el bucket de evidencias de entrega: {}", e.getMessage());
        }
    }
}
