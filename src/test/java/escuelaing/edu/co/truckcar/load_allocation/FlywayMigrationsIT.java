package escuelaing.edu.co.truckcar.load_allocation;

import escuelaing.edu.co.truckcar.load_allocation.migration.V3__MovePodMediaToObjectStorage;
import escuelaing.edu.co.truckcar.load_allocation.storage.PodObjectStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las migraciones de Flyway crean el esquema completo en una base vacía y coinciden con las entidades: el contexto JPA
 * arranca con ddl-auto=validate (el de application.yml), así que una columna o tabla que falte hace fallar el test.
 * Misma imagen que docker-compose y el CI (PostGIS 16). Sin Docker se omite (@Testcontainers(disabledWithoutDocker)).
 */
@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(V3__MovePodMediaToObjectStorage.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Migraciones Flyway de Load-Allocation (KAN-45)")
class FlywayMigrationsIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("truckcar_db")
            .withUsername("truckcar_admin")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=load_allocation,public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // @DataJpaTest fuerza create-drop: aquí se quiere el validate de producción.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // @DataJpaTest valida el esquema con una conexión anterior a que Flyway cree load_allocation: sin esto busca
        // las tablas en public. El @SpringBootTest del CI y Docker (search_path del rol) no lo necesitan.
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "load_allocation");
    }

    /** V3 necesita el cliente S3 solo si hay evidencias antiguas que trasladar; en una base vacía no lo usa. */
    @MockBean
    PodObjectStorage storage;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("Una base vacía aplica todas las migraciones en orden y sin errores")
    void anEmptyDatabaseAppliesEveryMigration() {
        List<String> applied = jdbc.queryForList(
                "SELECT version FROM load_allocation.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                String.class);
        assertThat(applied).containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    @DisplayName("Existen las tablas del servicio con sus índices")
    void tablesAndIndexesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'load_allocation'", String.class);
        assertThat(tables).contains("freight_loads", "freight_load_discarded_drivers", "freight_load_pod_media",
                "load_allocation_outbox_events", "ingested_events");

        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'load_allocation' AND tablename = 'freight_loads'",
                String.class);
        assertThat(indexes).contains("idx_load_status", "idx_generator_id", "idx_assignment_notified",
                "idx_assigned_driver");
    }

    @Test
    @DisplayName("La consulta del scheduler de vencimientos puede usar idx_assignment_notified")
    void timeoutSchedulerQueryUsesItsIndex() {
        // Con la tabla vacía el planificador prefiere Seq Scan; se desactiva para ver qué índice elige.
        String plan = String.join("\n", jdbc.execute((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                var rows = statement.executeQuery("EXPLAIN SELECT * FROM load_allocation.freight_loads"
                        + " WHERE assignment_status = 'NOTIFIED' AND assignment_status_changed_at < now()");
                var lines = new java.util.ArrayList<String>();
                while (rows.next()) {
                    lines.add(rows.getString(1));
                }
                statement.execute("RESET enable_seqscan");
                return lines;
            }
        }));
        assertThat(plan).contains("idx_assignment_notified");
    }

    @Test
    @DisplayName("Los CHECK rechazan un estado de carga desconocido")
    void statusChecksRejectUnknownValues() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO load_allocation.freight_loads (generator_company_id, generator_company_name,"
                                + " cargo_description, weight_tons, required_truck_type, origin_city, origin_department,"
                                + " origin_latitude, origin_longitude, destination_city, destination_department,"
                                + " destination_latitude, destination_longitude, offered_budget, status, published_at,"
                                + " assignment_attempts)"
                                + " VALUES ('g', 'G', 'x', 1, 'TURBO', 'A', 'DA', 4.7, -74.1, 'B', 'DB', 5.5, -73.4,"
                                + " 1000, 'PERDIDA', now(), 0)"))
                .hasMessageContaining("freight_loads_status_check");
    }
}
