package it.rebyte.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import it.rebyte.user.domain.User;

/**
 * Accesso a {@code user_db.user}, keyed dal claim Cognito {@code sub} (USER-01).
 */
public interface UserRepository extends JpaRepository<User, String> {

    boolean existsByUsername(String username);
}
