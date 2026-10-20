package it.rebyte.user.config;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.nimbusds.jose.jwk.RSAKey;

/**
 * Coppia di chiavi RSA per il minter {@code local-auth} (D-26, D-27).
 *
 * <p>Attivo solo sotto {@code @Profile("local-auth")} — questo e cio che
 * rende strutturalmente impossibile che il minter esista in una build
 * {@code cognito} (D-26). {@code local-auth} e solo per sviluppo: il minter
 * emette un token per qualunque {@code sub}/gruppi richiesti senza alcuna
 * verifica di credenziali, e non viene mai deployato. Non e un "fallback di
 * produzione" (correzione della vecchia dicitura di {@code .claude/CLAUDE.md},
 * D-29): un vero fallback richiederebbe verificare credenziali, cioe hash di
 * password in {@code user_db}, che D-02 vieta. Conseguenza accettata: nessun
 * fallback se Cognito e irraggiungibile in produzione.
 *
 * <p>La chiave e generata una volta per processo e mai persistita — se il
 * servizio riparte, la chiave cambia e i token precedenti smettono di
 * validare, il che e accettabile per uno strumento di solo sviluppo.
 */
@Configuration
@Profile("local-auth")
public class LocalAuthKeyConfig {

    private static final int RSA_KEY_SIZE_BITS = 2048;

    @Bean
    RSAKey localAuthRsaKey() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(RSA_KEY_SIZE_BITS);
        KeyPair keyPair = generator.generateKeyPair();

        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
    }
}
