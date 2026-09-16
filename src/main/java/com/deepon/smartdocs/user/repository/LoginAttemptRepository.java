package com.deepon.smartdocs.user.repository;

import com.deepon.smartdocs.user.entity.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    /** Retention (design doc section 8.3): without this the table becomes the largest in the database within a year. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM LoginAttempt a WHERE a.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
