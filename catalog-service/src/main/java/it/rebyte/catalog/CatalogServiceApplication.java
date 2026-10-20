package it.rebyte.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Bootstrap di catalog-service (listing, categorie con attributi ereditati,
 * proiezione di ricerca Meilisearch).
 *
 * <p>{@code @EnableFeignClients} qui, come user-service non ne ha ancora
 * bisogno finche il piano 02-03 non introduce il client verso
 * {@code GET /internal/users/{sub}} (il gate {@code publish_software}) —
 * dichiarato ora cosi nessun piano successivo deve riaprire questa classe.
 * Lo scheduling ({@code @EnableScheduling}) vive su
 * {@link it.rebyte.catalog.config.SchedulingConfig}, non qui, rispecchiando
 * lo split di user-service.
 */
@SpringBootApplication
@EnableFeignClients
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
