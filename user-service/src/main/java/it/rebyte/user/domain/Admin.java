package it.rebyte.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Admin row, keyed by the same {@code sub} as the {@code User} row (D-01,
 * D-08). No password column: Cognito {@code ADMIN} group membership is what
 * grants access. This row anchors the domain ({@code Moderation.admin},
 * {@code Category} back-references) and is <b>never</b> an authorization
 * source (D-08) — created once, never deleted, even on demotion (D-09),
 * because {@code Moderation.admin} is a mandatory {@code [1..1]} reference
 * the binding model requires and deleting on demotion would orphan
 * moderation history.
 *
 * <p><b>Resolves a genuine internal contradiction in the binding
 * document</b> (ch. 13 to be updated verbatim by plan 01-06): ch. 10.6's
 * prose enumerates ten surrogate-bearing classes and excludes {@code
 * Admin}; ch. 12 keys {@code Admin} by {@code username {id}}; but 10.6's
 * own identifier table (line 1286) gives {@code Admin} {@code id: UUID}.
 * Keying by {@code sub} — the same amendment D-01 already applies to
 * {@code User} — picks neither broken side: it is a real identifier (not a
 * surrogate ch. 12 invented), it is not {@code username} (which stays
 * mutable and cannot be a stable key), and per D-01 AMENDED it is typed
 * {@code String}/{@code VARCHAR(64)} {@code ascii} rather than {@link
 * java.util.UUID} because Cognito does not guarantee {@code sub} is
 * RFC-UUID-shaped.
 */
@Entity
@Table(name = "admin")
public class Admin {

    @Id
    @Column(name = "id", length = 64, nullable = false, updatable = false)
    private String id;

    protected Admin() {
        // required by JPA
    }

    public Admin(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
