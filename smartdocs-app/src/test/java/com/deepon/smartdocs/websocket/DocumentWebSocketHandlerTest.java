package com.deepon.smartdocs.websocket;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.common.IdGenerator;
import com.deepon.smartdocs.document.service.DocumentService;
import com.deepon.smartdocs.realtime.RoomRegistry;
import com.deepon.smartdocs.realtime.SessionRegistry;
import com.deepon.smartdocs.revision.entity.DocumentRevision;
import com.deepon.smartdocs.revision.repository.DocumentRevisionRepository;
import com.deepon.smartdocs.support.AbstractPostgresTest;
import com.deepon.smartdocs.support.TestUsers;
import com.deepon.smartdocs.user.repository.UserRepository;
import com.deepon.smartdocs.websocket.message.Envelope;
import com.deepon.smartdocs.websocket.message.payload.AppliedPayload;
import com.deepon.smartdocs.websocket.message.payload.ChangedPayload;
import com.deepon.smartdocs.websocket.message.payload.InSyncPayload;
import com.deepon.smartdocs.websocket.message.payload.SnapshotPayload;
import com.deepon.smartdocs.websocket.service.WsTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The first real-port WebSocket test in this repo (every other
 * {@code @SpringBootTest} class here uses {@code WebApplicationType.NONE} or
 * the MockMvc slice) — protocol-level behavior needs an actual upgraded
 * connection, which a real port is the only way to get (design doc build
 * order steps 3-6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "smartdocs.websocket.allow-missing-origin=true")
class DocumentWebSocketHandlerTest extends AbstractPostgresTest {

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
    private RoomRegistry roomRegistry;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private DocumentRevisionRepository revisionRepository;

    private final StandardWebSocketClient client = new StandardWebSocketClient();

    private UUID userId;
    private Actor actor;

    @BeforeEach
    void createTestUser() {
        actor = TestUsers.createActor(userRepository, idGenerator, clock);
        userId = actor.userId();
    }

    private String wsUrl(String ticket) {
        return "ws://localhost:" + port + "/ws" + (ticket != null ? "?ticket=" + ticket : "");
    }

    private WebSocketSession connect(String ticket, WebSocketHandler handler) throws Exception {
        return client.execute(handler, wsUrl(ticket)).get(5, TimeUnit.SECONDS);
    }

    private String issueTicket() {
        return wsTicketService.issue(userId, "127.0.0.1").rawToken();
    }

    @Test
    void connectsWithAValidTicket() {
        assertThatCode(() -> connect(issueTicket(), new RecordingHandler()).close())
                .doesNotThrowAnyException();
    }

    @Test
    void connectingWithoutATicketIsRejected() {
        assertThatThrownBy(() -> connect(null, new RecordingHandler()))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void connectingWithAnAlreadyUsedTicketIsRejected() throws Exception {
        String ticket = issueTicket();
        connect(ticket, new RecordingHandler()).close();

        assertThatThrownBy(() -> connect(ticket, new RecordingHandler()))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void pingReceivesAPong() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WebSocketSession session = connect(issueTicket(), handler);

        session.sendMessage(new TextMessage("{\"v\":1,\"type\":\"ping\",\"msgId\":\"p1\",\"ts\":0,\"payload\":{}}"));

        Envelope reply = handler.nextEnvelope();
        assertThat(reply.type()).isEqualTo("pong");
        session.close();
    }

    @Test
    void malformedJsonReturnsMalformedAndTheFourthCloses1008() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WebSocketSession session = connect(issueTicket(), handler);

        for (int i = 0; i < 3; i++) {
            session.sendMessage(new TextMessage("not json at all"));
            Envelope reply = handler.nextEnvelope();
            assertThat(reply.type()).isEqualTo("error");
        }
        session.sendMessage(new TextMessage("still not json"));

        CloseStatus closeStatus = handler.awaitClose();
        assertThat(closeStatus.getCode()).isEqualTo(1008);
    }

    @Test
    void subscribingAtTheCurrentKnownVersionTransfersNoBody() throws Exception {
        var document = documentService.create(actor, "Sync Test", "hello");
        RecordingHandler handler = new RecordingHandler();
        WebSocketSession session = connect(issueTicket(), handler);

        session.sendMessage(new TextMessage(
                "{\"v\":1,\"type\":\"doc.subscribe\",\"msgId\":\"s1\",\"ts\":0,\"payload\":{\"documentId\":\"%s\",\"knownVersion\":%d}}"
                        .formatted(document.getId(), document.getVersion())));

        Envelope reply = handler.nextEnvelope();
        assertThat(reply.type()).isEqualTo("doc.in_sync");
        session.close();
    }

    @Test
    void subscribingWithNoKnownVersionReceivesASnapshot() throws Exception {
        var document = documentService.create(actor, "Snapshot Test", "hello world");
        RecordingHandler handler = new RecordingHandler();
        WebSocketSession session = connect(issueTicket(), handler);

        session.sendMessage(new TextMessage(
                "{\"v\":1,\"type\":\"doc.subscribe\",\"msgId\":\"s1\",\"ts\":0,\"payload\":{\"documentId\":\"%s\"}}"
                        .formatted(document.getId())));

        Envelope reply = handler.nextEnvelope();
        assertThat(reply.type()).isEqualTo("doc.snapshot");
        SnapshotPayload payload = new WsMessageCodec(new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()))
                .payloadAs(reply, SnapshotPayload.class);
        assertThat(payload.content()).isEqualTo("hello world");
        session.close();
    }

    @Test
    void subscribingToAnotherUsersDocumentReturnsNotFound() throws Exception {
        Actor otherOwner = TestUsers.createActor(userRepository, idGenerator, clock);
        var document = documentService.create(otherOwner, "Not Mine", "secret");
        RecordingHandler handler = new RecordingHandler();
        WebSocketSession session = connect(issueTicket(), handler);

        session.sendMessage(new TextMessage(
                "{\"v\":1,\"type\":\"doc.subscribe\",\"msgId\":\"s1\",\"ts\":0,\"payload\":{\"documentId\":\"%s\"}}"
                        .formatted(document.getId())));

        Envelope reply = handler.nextEnvelope();
        assertThat(reply.type()).isEqualTo("error");
        session.close();
    }

    @Test
    void roomGaugeReturnsToZeroAfterBothTabsClose() throws Exception {
        var document = documentService.create(actor, "Room Test", "content");
        RecordingHandler handlerA = new RecordingHandler();
        RecordingHandler handlerB = new RecordingHandler();
        WebSocketSession sessionA = connect(issueTicket(), handlerA);
        WebSocketSession sessionB = connect(issueTicket(), handlerB);

        String subscribeFrame = "{\"v\":1,\"type\":\"doc.subscribe\",\"msgId\":\"s1\",\"ts\":0,\"payload\":{\"documentId\":\"%s\"}}"
                .formatted(document.getId());
        sessionA.sendMessage(new TextMessage(subscribeFrame));
        handlerA.nextEnvelope();
        sessionB.sendMessage(new TextMessage(subscribeFrame));
        handlerB.nextEnvelope();

        assertThat(roomRegistry.subscribers(document.getId())).hasSize(2);

        sessionA.close();
        sessionB.close();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(roomRegistry.countActiveRooms()).isZero());
    }

    @Test
    void renamingOverRestBroadcastsDocRenamedToSubscribers() throws Exception {
        var document = documentService.create(actor, "Old Title", "content");
        RecordingHandler handler = new RecordingHandler();
        WebSocketSession session = connect(issueTicket(), handler);
        subscribe(session, handler, document.getId());

        documentService.rename(actor, document.getId(), document.getVersion(), "New Title");

        Envelope renamed = handler.nextEnvelope();
        assertThat(renamed.type()).isEqualTo("doc.renamed");
        var payload = handler.codec.payloadAs(renamed, com.deepon.smartdocs.websocket.message.payload.RenamedPayload.class);
        assertThat(payload.title()).isEqualTo("New Title");
        session.close();
    }

    @Test
    void deletingOverRestBroadcastsDocDeletedAndEvictsTheRoom() throws Exception {
        var document = documentService.create(actor, "To Delete", "content");
        RecordingHandler handler = new RecordingHandler();
        WebSocketSession session = connect(issueTicket(), handler);
        subscribe(session, handler, document.getId());

        documentService.softDelete(actor, document.getId(), document.getVersion());

        Envelope deleted = handler.nextEnvelope();
        assertThat(deleted.type()).isEqualTo("doc.deleted");
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(roomRegistry.subscribers(document.getId())).isEmpty());
        session.close();
    }

    @Test
    void updateWithoutSubscribingFirstReturnsNotSubscribed() throws Exception {
        var document = documentService.create(actor, "Not Subscribed", "start");
        RecordingHandler handler = new RecordingHandler();
        WebSocketSession session = connect(issueTicket(), handler);

        session.sendMessage(new TextMessage(
                "{\"v\":1,\"type\":\"doc.update\",\"msgId\":\"u1\",\"ts\":0,\"payload\":{\"documentId\":\"%s\",\"baseVersion\":1,\"content\":\"nope\"}}"
                        .formatted(document.getId())));

        Envelope reply = handler.nextEnvelope();
        assertThat(reply.type()).isEqualTo("error");
        session.close();
    }

    @Test
    void updateAppliesAndAcksTheOriginWithTheNewVersion() throws Exception {
        var document = documentService.create(actor, "Update Test", "v1");
        RecordingHandler handler = new RecordingHandler();
        WebSocketSession session = connect(issueTicket(), handler);
        subscribe(session, handler, document.getId());

        session.sendMessage(new TextMessage(
                "{\"v\":1,\"type\":\"doc.update\",\"msgId\":\"u1\",\"ts\":0,\"payload\":{\"documentId\":\"%s\",\"baseVersion\":%d,\"content\":\"v2\"}}"
                        .formatted(document.getId(), document.getVersion())));

        Envelope reply = handler.nextEnvelope();
        assertThat(reply.type()).isEqualTo("doc.applied");
        assertThat(reply.msgId()).isEqualTo("u1");
        AppliedPayload payload = handler.codec.payloadAs(reply, AppliedPayload.class);
        assertThat(payload.version()).isEqualTo(document.getVersion() + 1);
        assertThat(payload.changed()).isTrue();
        assertThat(payload.overwrote()).isFalse();
        session.close();
    }

    @Test
    void resendingTheSameMsgIdAndContentProducesExactlyOneRevisionRow() throws Exception {
        var document = documentService.create(actor, "Idempotency Test", "v1");
        RecordingHandler handler = new RecordingHandler();
        WebSocketSession session = connect(issueTicket(), handler);
        subscribe(session, handler, document.getId());

        String updateFrame = "{\"v\":1,\"type\":\"doc.update\",\"msgId\":\"same-id\",\"ts\":0,\"payload\":{\"documentId\":\"%s\",\"baseVersion\":%d,\"content\":\"v2\"}}"
                .formatted(document.getId(), document.getVersion());

        session.sendMessage(new TextMessage(updateFrame));
        AppliedPayload first = handler.codec.payloadAs(handler.nextEnvelope(), AppliedPayload.class);
        assertThat(first.changed()).isTrue();

        // A client retry: same msgId, same content. The server has no
        // separate dedup table — the hash short-circuit already makes this
        // safe to resend, which is the point (design doc edge case 16).
        session.sendMessage(new TextMessage(updateFrame));
        AppliedPayload second = handler.codec.payloadAs(handler.nextEnvelope(), AppliedPayload.class);
        assertThat(second.changed()).isFalse();
        assertThat(second.version()).isEqualTo(first.version());

        assertThat(revisionRepository.countByDocumentId(document.getId())).isEqualTo(2); // create() + the one real update
        session.close();
    }

    @Test
    void updateBroadcastsToTheOtherSubscriberButNotBackToTheSender() throws Exception {
        var document = documentService.create(actor, "Broadcast Test", "v1");
        RecordingHandler handlerA = new RecordingHandler();
        RecordingHandler handlerB = new RecordingHandler();
        WebSocketSession sessionA = connect(issueTicket(), handlerA);
        WebSocketSession sessionB = connect(issueTicket(), handlerB);
        subscribe(sessionA, handlerA, document.getId());
        subscribe(sessionB, handlerB, document.getId());

        sessionA.sendMessage(new TextMessage(
                "{\"v\":1,\"type\":\"doc.update\",\"msgId\":\"u1\",\"ts\":0,\"payload\":{\"documentId\":\"%s\",\"baseVersion\":%d,\"content\":\"from A\"}}"
                        .formatted(document.getId(), document.getVersion())));

        Envelope ack = handlerA.nextEnvelope();
        assertThat(ack.type()).isEqualTo("doc.applied");

        Envelope changed = handlerB.nextEnvelope();
        assertThat(changed.type()).isEqualTo("doc.changed");
        ChangedPayload payload = handlerB.codec.payloadAs(changed, ChangedPayload.class);
        assertThat(payload.content()).isEqualTo("from A");

        sessionA.close();
        sessionB.close();
    }

    @Test
    void resendingIdenticalContentIsANoOpWithNoRevisionAndNoBroadcast() throws Exception {
        var document = documentService.create(actor, "Noop Test", "same content");
        RecordingHandler handlerA = new RecordingHandler();
        RecordingHandler handlerB = new RecordingHandler();
        WebSocketSession sessionA = connect(issueTicket(), handlerA);
        WebSocketSession sessionB = connect(issueTicket(), handlerB);
        subscribe(sessionA, handlerA, document.getId());
        subscribe(sessionB, handlerB, document.getId());

        sessionA.sendMessage(new TextMessage(
                "{\"v\":1,\"type\":\"doc.update\",\"msgId\":\"u1\",\"ts\":0,\"payload\":{\"documentId\":\"%s\",\"baseVersion\":%d,\"content\":\"same content\"}}"
                        .formatted(document.getId(), document.getVersion())));

        Envelope ack = handlerA.nextEnvelope();
        AppliedPayload payload = handlerA.codec.payloadAs(ack, AppliedPayload.class);
        assertThat(payload.changed()).isFalse();
        assertThat(payload.version()).isEqualTo(document.getVersion());

        assertThat(handlerB.pollEnvelope(1, TimeUnit.SECONDS)).as("no broadcast for a no-op write").isNull();
        assertThat(revisionRepository.countByDocumentId(document.getId())).isEqualTo(1); // only the original create() revision

        sessionA.close();
        sessionB.close();
    }

    @Test
    void concurrentUpdatesProduceALostWriteRevisionRow() throws Exception {
        var document = documentService.create(actor, "Concurrency Test", "initial");
        long baseVersion = document.getVersion();

        RecordingHandler handlerA = new RecordingHandler();
        RecordingHandler handlerB = new RecordingHandler();
        WebSocketSession sessionA = connect(issueTicket(), handlerA);
        WebSocketSession sessionB = connect(issueTicket(), handlerB);
        subscribe(sessionA, handlerA, document.getId());
        subscribe(sessionB, handlerB, document.getId());

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var futureA = pool.submit(() -> {
                startLatch.await();
                sessionA.sendMessage(new TextMessage(
                        "{\"v\":1,\"type\":\"doc.update\",\"msgId\":\"a1\",\"ts\":0,\"payload\":{\"documentId\":\"%s\",\"baseVersion\":%d,\"content\":\"from A\"}}"
                                .formatted(document.getId(), baseVersion)));
                return null;
            });
            var futureB = pool.submit(() -> {
                startLatch.await();
                sessionB.sendMessage(new TextMessage(
                        "{\"v\":1,\"type\":\"doc.update\",\"msgId\":\"b1\",\"ts\":0,\"payload\":{\"documentId\":\"%s\",\"baseVersion\":%d,\"content\":\"from B\"}}"
                                .formatted(document.getId(), baseVersion)));
                return null;
            });
            startLatch.countDown();
            futureA.get(5, TimeUnit.SECONDS);
            futureB.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        handlerA.nextEnvelope(); // ack for A
        handlerB.nextEnvelope(); // ack for B

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<DocumentRevision> revisions = revisionRepository.findAll().stream()
                    .filter(r -> r.getDocumentId().equals(document.getId()))
                    .toList();
            boolean anyOverwrote = revisions.stream()
                    .anyMatch(r -> r.getBaseVersion() != null && r.getBaseVersion() != r.getVersion() - 1);
            assertThat(anyOverwrote).as("one of the two concurrent writes must have overwritten the other").isTrue();
        });

        sessionA.close();
        sessionB.close();
    }

    @Test
    void versionsObservedByAClientAreStrictlyIncreasing() throws Exception {
        var document = documentService.create(actor, "Monotonic Test", "v0");
        RecordingHandler handlerA = new RecordingHandler();
        RecordingHandler handlerB = new RecordingHandler();
        WebSocketSession sessionA = connect(issueTicket(), handlerA);
        WebSocketSession sessionB = connect(issueTicket(), handlerB);
        subscribe(sessionA, handlerA, document.getId());
        subscribe(sessionB, handlerB, document.getId());

        List<Long> observedByA = new ArrayList<>();
        long baseVersion = document.getVersion();

        for (int i = 0; i < 5; i++) {
            sessionA.sendMessage(new TextMessage(
                    "{\"v\":1,\"type\":\"doc.update\",\"msgId\":\"a-%d\",\"ts\":0,\"payload\":{\"documentId\":\"%s\",\"baseVersion\":%d,\"content\":\"a-%d\"}}"
                            .formatted(i, document.getId(), baseVersion, i)));
            AppliedPayload applied = handlerA.codec.payloadAs(handlerA.nextEnvelope(), AppliedPayload.class);
            observedByA.add(applied.version());
            baseVersion = applied.version();

            sessionB.sendMessage(new TextMessage(
                    "{\"v\":1,\"type\":\"doc.update\",\"msgId\":\"b-%d\",\"ts\":0,\"payload\":{\"documentId\":\"%s\",\"baseVersion\":%d,\"content\":\"b-%d\"}}"
                            .formatted(i, document.getId(), baseVersion, i)));
            handlerB.nextEnvelope(); // B's own ack — not asserted on here

            Envelope changed = handlerA.nextEnvelope(); // A's broadcast of B's write
            assertThat(changed.type()).isEqualTo("doc.changed");
            ChangedPayload payload = handlerA.codec.payloadAs(changed, ChangedPayload.class);
            observedByA.add(payload.version());
            baseVersion = payload.version();
        }

        for (int i = 1; i < observedByA.size(); i++) {
            assertThat(observedByA.get(i)).as("version at index %d must exceed the previous", i)
                    .isGreaterThan(observedByA.get(i - 1));
        }

        sessionA.close();
        sessionB.close();
    }

    @Test
    void reconnectingWithAStaleKnownVersionReceivesASnapshotOfARestWriteMadeWhileDisconnected() throws Exception {
        var document = documentService.create(actor, "Reconnect Test", "v0");
        RecordingHandler handler = new RecordingHandler();
        WebSocketSession session = connect(issueTicket(), handler);
        subscribe(session, handler, document.getId());

        session.sendMessage(new TextMessage(
                "{\"v\":1,\"type\":\"doc.update\",\"msgId\":\"u1\",\"ts\":0,\"payload\":{\"documentId\":\"%s\",\"baseVersion\":%d,\"content\":\"v1\"}}"
                        .formatted(document.getId(), document.getVersion())));
        AppliedPayload applied = handler.codec.payloadAs(handler.nextEnvelope(), AppliedPayload.class);
        long knownVersion = applied.version();

        session.close(); // kill the socket — simulates the network drop

        // A REST write while "away": DocumentService#updateContent publishes
        // no DocumentChangedEvent (only rename/softDelete broadcast), so
        // nothing but a resubscribe with a stale knownVersion can ever catch
        // a reconnecting client up on this change (design doc section 4.2, 9).
        var afterRestWrite = documentService.updateContent(actor, document.getId(), knownVersion, "v2-from-rest", null);

        RecordingHandler reconnectHandler = new RecordingHandler();
        WebSocketSession reconnected = connect(issueTicket(), reconnectHandler);
        reconnected.sendMessage(new TextMessage(
                "{\"v\":1,\"type\":\"doc.subscribe\",\"msgId\":\"sub2\",\"ts\":0,\"payload\":{\"documentId\":\"%s\",\"knownVersion\":%d}}"
                        .formatted(document.getId(), knownVersion)));

        Envelope reply = reconnectHandler.nextEnvelope();
        assertThat(reply.type()).isEqualTo("doc.snapshot");
        SnapshotPayload snapshot = reconnectHandler.codec.payloadAs(reply, SnapshotPayload.class);
        assertThat(snapshot.content()).isEqualTo("v2-from-rest");
        assertThat(snapshot.version()).isEqualTo(afterRestWrite.getVersion());

        reconnected.close();
    }

    private void subscribe(WebSocketSession session, RecordingHandler handler, UUID documentId) throws Exception {
        session.sendMessage(new TextMessage(
                "{\"v\":1,\"type\":\"doc.subscribe\",\"msgId\":\"sub\",\"ts\":0,\"payload\":{\"documentId\":\"%s\"}}"
                        .formatted(documentId)));
        handler.nextEnvelope(); // snapshot or in_sync, not asserted on here
    }

    /** Queues every inbound frame so the test can assert on it synchronously. */
    private static final class RecordingHandler extends TextWebSocketHandler {
        private final BlockingQueue<String> inbound = new LinkedBlockingQueue<>();
        private final BlockingQueue<CloseStatus> closes = new LinkedBlockingQueue<>();
        private final WsMessageCodec codec = new WsMessageCodec(new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            inbound.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closes.add(status);
        }

        Envelope nextEnvelope() throws InterruptedException {
            String raw = inbound.poll(5, TimeUnit.SECONDS);
            assertThat(raw).as("expected a frame within 5s").isNotNull();
            return codec.decode(raw);
        }

        /** @return the next frame, or null if none arrives within the timeout — used to assert a broadcast did NOT happen. */
        Envelope pollEnvelope(long timeout, TimeUnit unit) throws InterruptedException {
            String raw = inbound.poll(timeout, unit);
            return raw == null ? null : codec.decode(raw);
        }

        CloseStatus awaitClose() throws InterruptedException {
            CloseStatus status = closes.poll(5, TimeUnit.SECONDS);
            assertThat(status).as("expected the connection to close within 5s").isNotNull();
            return status;
        }
    }
}
