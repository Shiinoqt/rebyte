package it.rebyte.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import it.rebyte.user.domain.Admin;

public interface AdminRepository extends JpaRepository<Admin, String> {
}
