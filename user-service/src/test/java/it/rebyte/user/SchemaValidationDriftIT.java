package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Locale;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.user.support.MySqlTestBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;

/**
 * D-62: length limits are stated in both the Flyway column definitions and
 * {@code @Size} annotations on purpose — the schema documents the real
 * limit and stays traceable to ch. 10-12, and this test makes a
 * disagreement fail the build instead of surfacing as a runtime {@code
 * DataIntegrityViolationException}.
 *
 * <p>Drives the comparison from reflection over every {@code @Entity} in
 * {@code it.rebyte.user.domain}, not a hand-maintained list, so a column
 * added in a later phase without a matching annotation is caught too.
 */
@SpringBootTest
@ActiveProfiles("local-auth")
class SchemaValidationDriftIT extends MySqlTestBase {

    private static final String DOMAIN_PACKAGE = "it.rebyte.user.domain";

    @Autowired
    private DataSource dataSource;

    @Test
    void everySizeAnnotatedColumnAgreesWithItsSchemaLength() throws ClassNotFoundException {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        int checked = 0;
        for (var candidate : scanner.findCandidateComponents(DOMAIN_PACKAGE)) {
            Class<?> entityClass = Class.forName(candidate.getBeanClassName());
            String tableName = resolveTableName(entityClass);

            for (Field field : entityClass.getDeclaredFields()) {
                Size size = field.getAnnotation(Size.class);
                Column column = field.getAnnotation(Column.class);
                if (size == null || column == null || size.max() == Integer.MAX_VALUE) {
                    continue;
                }
                checked++;

                Integer actualLength = jdbcTemplate.queryForObject(
                        "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns "
                                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                        Integer.class, tableName, column.name());

                assertThat(actualLength)
                        .as("column %s.%s vs @Size(max=%d) on %s#%s", tableName, column.name(),
                                size.max(), entityClass.getSimpleName(), field.getName())
                        .isEqualTo(size.max());
            }
        }

        // Guards against the scan silently finding nothing (e.g. a package
        // rename) and this test passing vacuously.
        assertThat(checked).isPositive();
    }

    private static String resolveTableName(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        String raw = (table != null && !table.name().isBlank())
                ? table.name()
                : entityClass.getSimpleName().toLowerCase(Locale.ROOT);
        // `user` is quoted with backticks in @Table(name = "`user`") because
        // it is a reserved word in MySQL 8.4 — information_schema.columns
        // stores the bare identifier.
        return raw.replace("`", "");
    }
}
