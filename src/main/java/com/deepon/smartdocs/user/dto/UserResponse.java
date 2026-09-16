package com.deepon.smartdocs.user.dto;

import com.deepon.smartdocs.user.entity.AppUser;

import java.time.Instant;
import java.util.UUID;

/** Never carries {@code passwordHash}, {@code status}, or a session token (design doc section 7.1). */
public record UserResponse(UUID id, String email, String displayName, Instant createdAt) {

    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }
}
