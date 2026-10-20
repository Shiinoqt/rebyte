package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.user.support.MySqlTestBase;

/**
 * Test di fumo per il rischio di ricerca "Flyway su Boot 4.1" (01-RESEARCH.md
 * P2.6, D-63): parte contro un database MySQL vuoto e verifica che
 * {@code flyway_schema_history} esista davvero e riporti la versione 1
 * applicata con successo — non che lo schema "sembri" gia corretto.
 */
@SpringBootTest
@ActiveProfiles("local-auth")
class FlywayBootstrapIT extends MySqlTestBase {

    @Autowired
    private DataSource dataSource;

    @Test
    void migratesCleanSchemaOnStartup() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        Integer appliedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class);

        assertThat(appliedCount).isEqualTo(1);
    }
}
