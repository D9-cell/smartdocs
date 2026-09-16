package com.deepon.smartdocs.websocket.service;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.common.IdGenerator;
import com.deepon.smartdocs.support.AbstractPostgresTest;
import com.deepon.smartdocs.support.TestUsers;
import com.deepon.smartdocs.user.exception.RateLimitedException;
import com.deepon.smartdocs.user.repository.UserRepository;
import com.deepon.smartdocs.websocket.entity.WsTicket;
import com.deepon.smartdocs.websocket.repository.WsTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Design doc build order step 2: "a ticket redeems once and fails the second time." */
@SpringBootTest
class WsTicketServiceTest extends AbstractPostgresTest {

    @Autowired
    private WsTicketService wsTicketService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WsTicketRepository wsTicketRepository;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private Clock clock;

    private UUID userId;

    @BeforeEach
    void createTestUser() {
        Actor actor = TestUsers.createActor(userRepository, idGenerator, clock);
        userId = actor.userId();
    }

    @Test
    void issuedTicketRedeemsToTheIssuingUser() {
        WsTicketService.Issued issued = wsTicketService.issue(userId, "127.0.0.1");

        Optional<UUID> redeemed = wsTicketService.redeem(issued.rawToken());

        assertThat(redeemed).contains(userId);
    }

    @Test
    void aTicketRedeemsOnceAndFailsTheSecondTime() {
        WsTicketService.Issued issued = wsTicketService.issue(userId, "127.0.0.1");

        assertThat(wsTicketService.redeem(issued.rawToken())).isPresent();
        assertThat(wsTicketService.redeem(issued.rawToken())).isEmpty();
    }

    @Test
    void unknownTokenRedeemsToEmpty() {
        assertThat(wsTicketService.redeem("not-a-real-ticket")).isEmpty();
    }

    @Test
    void nullOrBlankTokenRedeemsToEmptyWithoutTouchingTheDatabase() {
        assertThat(wsTicketService.redeem(null)).isEmpty();
        assertThat(wsTicketService.redeem("")).isEmpty();
    }

    @Test
    void expiredTicketRedeemsToEmpty() {
        WsTicketService.Issued issued = wsTicketService.issue(userId, "127.0.0.1");
        WsTicket ticket = wsTicketRepository.findAll().stream()
                .filter(t -> t.getUserId().equals(userId))
                .findFirst().orElseThrow();
        // Simulate the TTL having already elapsed, the same technique
        // SessionServiceTest uses for idle/absolute expiry — the redeem
        // query's own WHERE clause is what's actually under test.
        ticket.setExpiresAt(clock.instant().minusSeconds(1));
        wsTicketRepository.saveAndFlush(ticket);

        assertThat(wsTicketService.redeem(issued.rawToken())).isEmpty();
    }

    @Test
    void eleventhTicketWithinAMinuteIsRateLimited() {
        for (int i = 0; i < 10; i++) {
            wsTicketService.issue(userId, "127.0.0.1");
        }
        assertThatThrownBy(() -> wsTicketService.issue(userId, "127.0.0.1"))
                .isInstanceOf(RateLimitedException.class);
    }
}
