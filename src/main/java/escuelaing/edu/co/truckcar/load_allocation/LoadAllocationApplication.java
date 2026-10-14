package escuelaing.edu.co.truckcar.load_allocation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LoadAllocationApplication {

	public static void main(String[] args) {
		SpringApplication.run(LoadAllocationApplication.class, args);
	}
}