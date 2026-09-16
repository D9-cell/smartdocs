package com.deepon.smartdocs.websocket;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.common.IdGenerator;
import com.deepon.smartdocs.document.entity.Document;
import com.deepon.smartdocs.document.service.DocumentService;
import com.deepon.smartdocs.realtime.OutboundSender;
import com.deepon.smartdocs.realtime.SessionHandle;
import com.deepon.smartdocs.realtime.SessionRegistry;
import com.deepon.smartdocs.support.AbstractPostgresTest;
import com.deepon.smartdocs.support.TestUsers;
import com.deepon.smartdocs.user.repository.UserRepository;
import com.deepon.smartdocs.websocket.message.ServerMessageType;
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
import static org.awaitility.Awaitility.await;

/**
 * design doc section 9: "a client accepting the handshake and then reading
 * nothing. Assert the session closes rather than the server heap growing."
 * Driven directly through {@link OutboundSender} with a tiny queue and a low
 * drop-close threshold (design doc edge case 32: "Persistent overflow
 * closes the session") rather than a genuinely stalled TCP peer — a burst
 * enqueued from a bare in-process loop reliably outruns the async drain
 * executor regardless of real socket/OS buffering, which is what makes this
 * deterministic instead of a flaky race against network timing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "smartdocs.websocket.allow-missing-origin=true",
        "smartdocs.websocket.outbound-queue-capacity=2",
        "smartdocs.websocket.outbound-queue-drop-close-threshold=3"
})
class DocumentWebSocketBackpressureTest extends AbstractPostgresTest {

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

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private OutboundSender outboundSender;

    private final StandardWebSocketClient client = new StandardWebSocketClient();
    private UUID userId;
    private Actor actor;

    @BeforeEach
    void createTestUser() {
        actor = TestUsers.createActor(userRepository, idGenerator, clock);
        userId = actor.userId();
    }

    @Test
    void aSessionThatFallsFarEnoughBehindGetsClosedInsteadOfLettingItsQueueGrowUnbounded() throws Exception {
        Document document = documentService.create(actor, "Backpressure Test", "content");
        ClosingHandler handler = new ClosingHandler();
        String ticket = wsTicketService.issue(userId, "127.0.0.1").rawToken();
        WebSocketSession session = client.execute(handler, "ws://localhost:" + port + "/ws?ticket=" + ticket)
                .get(5, TimeUnit.SECONDS);

        session.sendMessage(new TextMessage(
                "{\"v\":1,\"type\":\"doc.subscribe\",\"msgId\":\"sub\",\"ts\":0,\"payload\":{\"documentId\":\"%s\"}}"
                        .formatted(document.getId())));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(sessionRegistry.byUser(userId)).hasSize(1));
        SessionHandle handle = sessionRegistry.byUser(userId).get(0);

        // Every frame gets a distinct conflate key (a fresh random id) so
        // none of them collapse into one another the way doc.changed frames
        // for the same document would — this is what actually exercises the
        // queue-capacity path (capacity 2, close after 3 drops) instead of
        // the conflation path.
        for (int i = 0; i < 20; i++) {
            outboundSender.enqueue(handle, "{\"v\":1,\"type\":\"doc.renamed\",\"payload\":{}}",
                    UUID.randomUUID(), ServerMessageType.RENAMED);
        }

        CloseStatus closeStatus = handler.awaitClose();
        assertThat(closeStatus.getCode()).isEqualTo(1011);
    }

    private static final class ClosingHandler extends TextWebSocketHandler {
        private final BlockingQueue<CloseStatus> closes = new LinkedBlockingQueue<>();

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closes.add(status);
        }

        CloseStatus awaitClose() throws InterruptedException {
            CloseStatus status = closes.poll(5, TimeUnit.SECONDS);
            assertThat(status).as("expected the session to close rather than let the queue grow unbounded").isNotNull();
            return status;
        }
    }
}
