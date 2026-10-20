package it.rebyte.user.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Profilo utente, keyed dal claim Cognito {@code sub} (D-01, AMENDED; USER-01).
 *
 * <p>{@code id} e {@code String}, non {@link java.util.UUID}: AWS documenta
 * esplicitamente che {@code sub} non e garantito in formato RFC UUID
 * (01-RESEARCH.md P2.4). Un {@code UUID.fromString(sub)} lancerebbe
 * {@code IllegalArgumentException} su ogni richiesta autenticata di un utente
 * il cui {@code sub} non rientra nella forma canonica — non un edge case
 * raro, ma un'interruzione totale per l'utente colpito.
 *
 * <p>{@code registration} e impostato una sola volta, all'inserimento in
 * fase di JIT provisioning (D-07), ed e documentato come approssimazione:
 * accurato al secondo per il flusso normale signup -> confirm -> login, il
 * claim {@code auth_time} del token e stato scartato perche registra la
 * sessione corrente, non la creazione dell'account.
 *
 * <p>Entita mai esposta da un controller — solo DTO (MeResponse), per la
 * regola di layering del progetto.
 */
@Entity
@Table(name = "`user`")
public class User {

    @Id
    @Column(name = "id", length = 64, nullable = false, updatable = false)
    private String id;

    @NotNull
    @Size(min = 3, max = 30)
    @Column(name = "username", length = 30, nullable = false)
    private String username;

    @NotNull
    @Column(name = "registration", nullable = false, updatable = false)
    private Instant registration;

    @Size(max = 80)
    @Column(name = "location", length = 80)
    private String location;

    @Size(max = 500)
    @Column(name = "bio", length = 500)
    private String bio;

    /** Chiave oggetto S3, non URL (D-33). {@code null} finche l'utente non carica un avatar. */
    @Size(max = 255)
    @Column(name = "avatar", length = 255)
    private String avatar;

    protected User() {
        // richiesto da JPA
    }

    public User(String id, String username, Instant registration) {
        this.id = id;
        this.username = username;
        this.registration = registration;
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Instant getRegistration() {
        return registration;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }
}
