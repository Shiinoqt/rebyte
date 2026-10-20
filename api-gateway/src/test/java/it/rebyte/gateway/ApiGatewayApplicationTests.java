package it.rebyte.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifica che il contesto parta con l'unico profilo offline che autentica
 * (local-auth). Prima di questa revisione esisteva anche un profilo dev
 * senza autenticazione (D-28); rimosso, non resta piu nessuna modalita di
 * avvio senza token. Dipende dal minter locale del piano 01-01 per risolvere
 * l'issuer.
 */
@SpringBootTest
@ActiveProfiles("local-auth")
class ApiGatewayApplicationTests {

    @Test
    void contextLoads() {
    }
}
