package com.deepon.smartdocs.websocket;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.common.IdGenerator;
import com.deepon.smartdocs.support.AbstractPostgresTest;
import com.deepon.smartdocs.support.TestUsers;
import com.deepon.smartdocs.user.repository.UserRepository;
import com.deepon.smartdocs.websocket.service.WsTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design doc build order step 12: per-user connection cap and sustained
 * rate limit, each against config small enough to trip deterministically
 * in a fast test rather than needing hundreds of real messages.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "smartdocs.websocket.allow-missing-origin=true",
        "smartdocs.websocket.max-connections-per-user=2",
        "smartdocs.websocket.rate-limit.per-second=1",
        "smartdocs.websocket.rate-limit.burst=2"
})
class DocumentWebSocketHandlerLimitsTest extends AbstractPostgresTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WsTicketService wsTicketService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private Clock clock;

    private final StandardWebSocketClient client = new StandardWebSocketClient();
    private UUID userId;

    @BeforeEach
    void createTestUser() {
        userId = TestUsers.createActor(userRepository, idGenerator, clock).userId();
    }

    private WebSocketSession connect(ClosingHandler handler) throws Exception {
        String ticket = wsTicketService.issue(userId, "127.0.0.1").rawToken();
        return client.execute(handler, "ws://localhost:" + port + "/ws?ticket=" + ticket).get(5, TimeUnit.SECONDS);
    }

    @Test
    void aThirdConnectionEvictsTheOldestWithConnectionCapClose() throws Exception {
        ClosingHandler first = new ClosingHandler();
        ClosingHandler second = new ClosingHandler();
        ClosingHandler third = new ClosingHandler();

        WebSocketSession sessionA = connect(first);
        connect(second); // cap is 2 — both alive so far
        connect(third); // the 3rd connection evicts the oldest (sessionA)

        CloseStatus closeStatus = first.awaitClose();
        assertThat(closeStatus.getCode()).isEqualTo(4003);
        assertThat(sessionA.isOpen()).isFalse();
    }

    @Test
    void sustainedRateLimitBreachClosesWith4029() throws Exception {
        ClosingHandler handler = new ClosingHandler();
        WebSocketSession session = connect(handler);

        // Burst is 2, refill 1/s — six rapid pings in immediate succession
        // exhausts the bucket well before it can refill.
        for (int i = 0; i < 6; i++) {
            session.sendMessage(new TextMessage("{\"v\":1,\"type\":\"ping\",\"msgId\":\"p" + i + "\",\"ts\":0,\"payload\":{}}"));
        }

        CloseStatus closeStatus = handler.awaitClose();
        assertThat(closeStatus.getCode()).isEqualTo(4029);
    }

    /** Records the close status only — the limits tests don't need to inspect frame bodies. */
    private static final class ClosingHandler extends TextWebSocketHandler {
        private final BlockingQueue<CloseStatus> closes = new LinkedBlockingQueue<>();

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closes.add(status);
        }

        CloseStatus awaitClose() throws InterruptedException {
            CloseStatus status = closes.poll(5, TimeUnit.SECONDS);
            assertThat(status).as("expected the connection to close within 5s").isNotNull();
            return status;
        }
    }
}
