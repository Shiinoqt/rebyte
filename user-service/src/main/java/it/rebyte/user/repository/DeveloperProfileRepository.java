package it.rebyte.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import it.rebyte.user.domain.DeveloperProfile;

public interface DeveloperProfileRepository extends JpaRepository<DeveloperProfile, String> {
}
