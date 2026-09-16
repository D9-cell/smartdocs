package com.deepon.smartdocs.user.service;

import com.deepon.smartdocs.user.entity.AppUser;

import java.util.UUID;

public interface UserService {

    /** @throws com.deepon.smartdocs.user.exception.SessionInvalidException if the user is gone or soft-deleted. */
    AppUser getActiveById(UUID userId);
}
