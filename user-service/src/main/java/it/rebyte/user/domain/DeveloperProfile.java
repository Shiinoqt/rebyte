package it.rebyte.user.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Developer-profile activation (D-11, USER-05), its own table with {@code
 * user_sub} as both primary and foreign key — the literal relational
 * reading of ch. 10.6's external identifier for this class. A nullable
 * column on the {@code User} table would erase a class from the binding
 * model.
 */
@Entity
@Table(name = "developer_profile")
public class DeveloperProfile {

    @Id
    @Column(name = "user_sub", length = 64, nullable = false, updatable = false)
    private String userSub;

    @Column(name = "activated_at", nullable = false, updatable = false)
    private Instant activatedAt;

    protected DeveloperProfile() {
        // required by JPA
    }

    public DeveloperProfile(String userSub, Instant activatedAt) {
        this.userSub = userSub;
        this.activatedAt = activatedAt;
    }

    public String getUserSub() {
        return userSub;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }
}
