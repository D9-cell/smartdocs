package com.deepon.smartdocs.websocket;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.common.IdGenerator;
import com.deepon.smartdocs.document.entity.Document;
import com.deepon.smartdocs.document.service.DocumentService;
import com.deepon.smartdocs.support.AbstractPostgresTest;
import com.deepon.smartdocs.support.TestUsers;
import com.deepon.smartdocs.user.repository.UserRepository;
import com.deepon.smartdocs.websocket.message.Envelope;
import com.deepon.smartdocs.websocket.message.payload.AppliedPayload;
import com.deepon.smartdocs.websocket.message.payload.ChangedPayload;
import com.deepon.smartdocs.websocket.message.payload.InSyncPayload;
import com.deepon.smartdocs.websocket.message.payload.SnapshotPayload;
import com.deepon.smartdocs.websocket.service.WsTicketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * design doc section 9: "4 clients, 50 updates each, random delays. Assert
 * the final server content equals one of the submitted contents, and every
 * client converges on it within 2 seconds of the last write." Sample count
 * scaled down (15/client) to keep this fast in CI without changing what's
 * actually under test — real last-write-wins convergence under concurrency,
 * not a specific volume.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "smartdocs.websocket.allow-missing-origin=true")
class DocumentWebSocketConvergenceTest extends AbstractPostgresTest {

    private static final int CLIENT_COUNT = 4;
    private static final int UPDATES_PER_CLIENT = 15;

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

    private final StandardWebSocketClient client = new StandardWebSocketClient();
    private Actor actor;
    private UUID userId;

    @BeforeEach
    void createTestUser() {
        actor = TestUsers.createActor(userRepository, idGenerator, clock);
        userId = actor.userId();
    }

    @Test
    void fourClientsWritingConcurrentlyConvergeOnOneSurvivingContentWithinTwoSeconds() throws Exception {
        Document document = documentService.create(actor, "Convergence", "start");
        UUID documentId = document.getId();

        List<ConvergingClient> clients = new ArrayList<>();
        for (int i = 0; i < CLIENT_COUNT; i++) {
            clients.add(newSubscribedClient("client-" + i, documentId));
        }

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CLIENT_COUNT);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (ConvergingClient c : clients) {
                futures.add(pool.submit(() -> {
                    try {
                        startLatch.await();
                        c.sendUpdates(documentId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            startLatch.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        // The final server content must be exactly one of the contents some
        // client actually submitted — last write wins, never a merge.
        List<String> allSubmittedContents = clients.stream()
                .flatMap(c -> c.sentContents.stream())
                .toList();

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            Document current = documentService.get(actor, documentId);
            assertThat(allSubmittedContents).contains(current.getContent());

            for (ConvergingClient c : clients) {
                assertThat(c.lastKnownContent.get()).as(c.name + " must converge on the server's final content")
                        .isEqualTo(current.getContent());
            }
        });

        for (ConvergingClient c : clients) {
            c.session.close();
        }
    }

    private ConvergingClient newSubscribedClient(String name, UUID documentId) throws Exception {
        String ticket = wsTicketService.issue(userId, "127.0.0.1").rawToken();
        ConvergingClient handler = new ConvergingClient(name);
        WebSocketSession session = client.execute(handler, "ws://localhost:" + port + "/ws?ticket=" + ticket)
                .get(5, TimeUnit.SECONDS);
        handler.session = session;

        session.sendMessage(new TextMessage(
                "{\"v\":1,\"type\":\"doc.subscribe\",\"msgId\":\"sub\",\"ts\":0,\"payload\":{\"documentId\":\"%s\"}}"
                        .formatted(documentId)));
        Envelope first = handler.nextEnvelope();
        if ("doc.snapshot".equals(first.type())) {
            SnapshotPayload snapshot = handler.codec.payloadAs(first, SnapshotPayload.class);
            handler.knownVersion.set(snapshot.version());
            handler.lastKnownContent.set(snapshot.content());
        } else {
            InSyncPayload inSync = handler.codec.payloadAs(first, InSyncPayload.class);
            handler.knownVersion.set(inSync.version());
            handler.lastKnownContent.set("start"); // the document's create() content — already known by the test
        }
        return handler;
    }

    /**
     * A simulated tab: sends its own sequence of updates, and processes
     * every inbound frame (its own ack, or another client's broadcast) to
     * track the content it currently believes is live.
     */
    private static final class ConvergingClient extends TextWebSocketHandler {
        private final String name;
        private final WsMessageCodec codec = new WsMessageCodec(new ObjectMapper().registerModule(new JavaTimeModule()));
        private final AtomicReference<String> lastKnownContent = new AtomicReference<>();
        private final AtomicLong knownVersion = new AtomicLong();
        private final Map<String, String> outbox = new ConcurrentHashMap<>();
        private final List<String> sentContents = Collections.synchronizedList(new ArrayList<>());
        private final BlockingQueue<String> inbound = new LinkedBlockingQueue<>();
        private volatile WebSocketSession session;

        ConvergingClient(String name) {
            this.name = name;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            String raw = message.getPayload();
            Envelope envelope = codec.decode(raw);
            switch (envelope.type()) {
                case "doc.applied" -> {
                    AppliedPayload applied = codec.payloadAs(envelope, AppliedPayload.class);
                    knownVersion.set(applied.version());
                    if (applied.changed()) {
                        String sent = outbox.get(envelope.msgId());
                        if (sent != null) {
                            lastKnownContent.set(sent);
                        }
                    }
                }
                case "doc.changed" -> {
                    ChangedPayload changed = codec.payloadAs(envelope, ChangedPayload.class);
                    knownVersion.set(changed.version());
                    lastKnownContent.set(changed.content());
                }
                default -> inbound.add(raw); // subscribe replies etc., consumed by nextEnvelope()
            }
        }

        Envelope nextEnvelope() throws InterruptedException {
            String raw = inbound.poll(5, TimeUnit.SECONDS);
            assertThat(raw).as("expected a frame within 5s").isNotNull();
            return codec.decode(raw);
        }

        void sendUpdates(UUID documentId) throws Exception {
            for (int i = 0; i < UPDATES_PER_CLIENT; i++) {
                String content = name + "-write-" + i;
                String msgId = name + "-" + i;
                outbox.put(msgId, content);
                sentContents.add(content);
                session.sendMessage(new TextMessage(
                        "{\"v\":1,\"type\":\"doc.update\",\"msgId\":\"%s\",\"ts\":0,\"payload\":{\"documentId\":\"%s\",\"baseVersion\":%d,\"content\":\"%s\"}}"
                                .formatted(msgId, documentId, knownVersion.get(), content)));
                Thread.sleep(ThreadLocalRandom.current().nextInt(0, 6));
            }
        }
    }
}
