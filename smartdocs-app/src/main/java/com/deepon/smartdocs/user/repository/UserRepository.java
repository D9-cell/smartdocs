package com.deepon.smartdocs.user.repository;

import com.deepon.smartdocs.user.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<AppUser, UUID> {

    /** Case-insensitive lookup on the submitted email — see the class Javadoc on {@code AppUser}. */
    @Query("SELECT u FROM AppUser u WHERE LOWER(u.email) = LOWER(:email) AND u.deletedAt IS NULL")
    Optional<AppUser> findByEmailIgnoreCase(@Param("email") String email);

    Optional<AppUser> findByIdAndDeletedAtIsNull(UUID id);
}
