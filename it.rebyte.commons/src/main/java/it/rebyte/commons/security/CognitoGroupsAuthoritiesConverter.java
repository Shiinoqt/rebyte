package it.rebyte.commons.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Mappa il claim {@code cognito:groups} del JWT validato su
 * {@link GrantedAuthority} Spring Security (IDENT-03, D-12).
 *
 * <p>Il nome del claim mantiene il prefisso vendor {@code cognito:} invece di
 * un nome neutro come {@code groups}: un nome neutro costringerebbe il
 * converter a ramificare per profilo, e la mappatura reale di IDENT-03 non
 * sarebbe mai esercitata in locale (D-27) — sia il profilo {@code cognito}
 * che {@code local-auth} emettono lo stesso nome di claim, quindi lo sviluppo
 * quotidiano e gia il test di questo converter.
 *
 * <p>E una classe semplice, importata esplicitamente con {@code @Import} da
 * ogni servizio consumatore (D-12) — {@code it.rebyte.commons} e una libreria,
 * non uno starter Spring Boot: niente {@code AutoConfiguration.imports}, cosi
 * cio che e attivo in un servizio resta visibile leggendo la sua configurazione.
 *
 * <p>Null-safety: un claim assente, un valore {@code null} o una lista vuota
 * producono tutti una collezione vuota, mai una {@link NullPointerException}
 * (IDENT-03, edge: empty). I duplicati collassano in una sola authority
 * (IDENT-03, edge: adjacency), e la collezione risultante e un
 * {@link LinkedHashSet}: nessun chiamante deve dipendere dall'ordine di
 * iterazione (IDENT-03, edge: ordering).
 */
public class CognitoGroupsAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String GROUPS_CLAIM = "cognito:groups";
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<String> groups = Optional.ofNullable(jwt.getClaimAsStringList(GROUPS_CLAIM))
                .orElseGet(List::of);

        Collection<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (String group : groups) {
            authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + group.toUpperCase(Locale.ROOT)));
        }
        return authorities;
    }
}
