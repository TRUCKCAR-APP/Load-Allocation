package escuelaing.edu.co.truckcar.load_allocation.tracking;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TrackingClockConfig {

    /** Reloj inyectable: las pruebas fijan el instante para verificar los vencimientos de los enlaces. */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
