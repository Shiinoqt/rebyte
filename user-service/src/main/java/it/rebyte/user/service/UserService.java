package it.rebyte.user.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import it.rebyte.user.domain.User;
import it.rebyte.user.repository.UserRepository;

/**
 * Provisioning just-in-time del profilo utente sul primo accesso autenticato
 * (D-05, USER-01).
 */
@Service
public class UserService {

    private static final String HANDLE_PREFIX = "user_";
    private static final String EMPTY_SUB_FALLBACK_HEX = "00000000";

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Risolve il profilo del chiamante a partire dal {@link Jwt} gia
     * validato: legge {@code sub}, cerca la riga per id e, se assente, la
     * crea. Gli handle generati non possono collidere per costruzione
     * ({@code sub} e unico), quindi non serve alcun retry loop ne un
     * suffisso di disambiguazione (D-05).
     *
     * <p>Due prime richieste concorrenti per lo stesso {@code sub} sono un
     * inserimento idempotente: la violazione di chiave primaria della
     * richiesta perdente viene assorbita, non propagata — entrambe le
     * richieste ottengono 200 con la stessa riga (D-05, edge: concurrency).
     *
     * <p>Deliberatamente NON {@code @Transactional}: se questo metodo aprisse
     * una singola transazione ambiente attorno a {@code findById} +
     * {@code saveAndFlush}, la {@link DataIntegrityViolationException}
     * lanciata da {@code saveAndFlush} marcherebbe quella transazione
     * rollback-only PRIMA che il catch qui sotto la intercetti — il
     * successivo {@code findById} di rilettura parteciperebbe alla STESSA
     * transazione gia condannata, e il commit finale fallirebbe con
     * {@code UnexpectedRollbackException} anche se il chiamante ha "vinto"
     * la rilettura (osservato in {@code UserJitProvisioningIT}). Lasciando
     * ogni chiamata al repository nella propria transazione implicita di
     * Spring Data JPA, il rollback del solo inserimento fallito resta isolato
     * e la rilettura successiva e una transazione pulita e indipendente.
     */
    public User resolveOrProvision(Jwt jwt) {
        String sub = jwt.getSubject();
        return userRepository.findById(sub).orElseGet(() -> provision(sub));
    }

    private User provision(String sub) {
        // Troncato a microsecondi: la colonna e DATETIME(6), e un valore
        // restituito prima del round-trip nel DB (con precisione al
        // nanosecondo di Instant.now()) altrimenti differirebbe dallo stesso
        // valore riletto dopo la persistenza, rompendo l'idempotenza del
        // corpo di risposta (osservato in UserJitProvisioningIT).
        Instant registration = Instant.now().truncatedTo(ChronoUnit.MICROS);
        User user = new User(sub, generateHandle(sub), registration);
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException raceLost) {
            // Un'altra richiesta concorrente ha vinto l'inserimento sullo
            // stesso sub: la riga esiste gia, la si rilegge (D-05).
            return userRepository.findById(sub).orElseThrow(() -> raceLost);
        }
    }

    /**
     * Genera l'handle {@code user_<8 esadecimali>} per un {@code sub} mai
     * visto (D-05). Regola esatta, riprodotta byte-identica dal piano 01-04
     * (D-52) senza un helper condiviso:
     *
     * <ol>
     *   <li>Se {@code sub} ha almeno 8 caratteri E i primi 8, messi in
     *       minuscolo, sono tutti cifre esadecimali valide (0-9a-f):
     *       l'handle usa esattamente quegli 8 caratteri minuscoli. Questo e
     *       il caso comune — uno UUID Cognito canonico inizia gia con 8
     *       cifre esadecimali.</li>
     *   <li>Altrimenti (sub piu corto di 8 caratteri, o i primi 8 caratteri
     *       non sono tutti esadecimali — un formato che AWS non garantisce,
     *       D-01 AMENDED): si calcola la "proiezione hex-safe" del sub,
     *       cioe la rappresentazione esadecimale minuscola dei suoi byte
     *       UTF-8 ({@link HexFormat#formatHex(byte[])}), ripetuta se
     *       necessario per raggiungere almeno 8 caratteri (un sub vuoto usa
     *       il fallback fisso {@code "00000000"}), e se ne prendono i primi
     *       8 caratteri. Questa proiezione e totale su qualsiasi stringa e
     *       non lancia mai un'eccezione.</li>
     * </ol>
     */
    public static String generateHandle(String sub) {
        String candidate = sub.length() >= 8 ? sub.substring(0, 8).toLowerCase(Locale.ROOT) : null;
        String hex8 = (candidate != null && isHex(candidate)) ? candidate : hexSafeProjection(sub);
        return HANDLE_PREFIX + hex8;
    }

    private static boolean isHex(String value) {
        return value.chars().allMatch(c -> Character.digit(c, 16) != -1);
    }

    private static String hexSafeProjection(String sub) {
        String hex = HexFormat.of().formatHex(sub.getBytes(StandardCharsets.UTF_8));
        if (hex.isEmpty()) {
            hex = EMPTY_SUB_FALLBACK_HEX;
        }
        StringBuilder padded = new StringBuilder(hex);
        while (padded.length() < 8) {
            padded.append(hex);
        }
        return padded.substring(0, 8);
    }
}
