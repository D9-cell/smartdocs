package com.deepon.smartdocs.support;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.common.IdGenerator;
import com.deepon.smartdocs.user.entity.AppUser;
import com.deepon.smartdocs.user.repository.UserRepository;

import java.time.Clock;
import java.util.UUID;

/** A throwaway {@code app_user} row so document-domain tests have a valid owner FK target. */
public final class TestUsers {

    private TestUsers() {
    }

    public static Actor createActor(UserRepository userRepository, IdGenerator idGenerator, Clock clock) {
        UUID id = idGenerator.newId();
        var now = clock.instant();
        AppUser user = new AppUser(id, "test-" + id + "@example.com", "Test User", "{noop}unused", now, now);
        userRepository.saveAndFlush(user);
        return Actor.human(id);
    }
}
