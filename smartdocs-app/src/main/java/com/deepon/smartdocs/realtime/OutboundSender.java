package com.deepon.smartdocs.realtime;

import com.deepon.smartdocs.websocket.message.ServerMessageType;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One bounded queue per session (design doc section 5.2). Conflation before
 * enqueue collapses any already-queued {@code doc.changed} for the same
 * document to the newest — only the latest full state matters, the
 * strongest argument in the design doc for full-state payloads at this
 * stage. Draining runs on a shared executor rather than one thread per
 * session, which collapses at scale.
 */
@Component
public class OutboundSender {

    private static final Logger log = LoggerFactory.getLogger(OutboundSender.class);

    /** design doc edge case 32: "Persistent overflow closes the session." */
    private static final CloseStatus SLOW_CONSUMER_CLOSE = new CloseStatus(1011, "Slow consumer: outbound queue persistently overflowing");

    private final int queueCapacity;
    private final int dropCloseThreshold;
    private final MeterRegistry meterRegistry;
    private final ExecutorService drainExecutor;
    private final Map<String, SessionQueue> queues = new ConcurrentHashMap<>();

    public OutboundSender(@Value("${smartdocs.websocket.outbound-queue-capacity:32}") int queueCapacity,
                           @Value("${smartdocs.websocket.outbound-queue-drop-close-threshold:64}") int dropCloseThreshold,
                           MeterRegistry meterRegistry) {
        this.queueCapacity = queueCapacity;
        this.dropCloseThreshold = dropCloseThreshold;
        this.meterRegistry = meterRegistry;
        this.drainExecutor = Executors.newCachedThreadPool();
    }

    /**
     * @param conflateKey non-null only for a {@code doc.changed} frame — the document id it carries.
     * @param type        the frame's server message type, for {@code ws_frames_out_total}.
     */
    public void enqueue(SessionHandle handle, String encodedFrame, UUID conflateKey, ServerMessageType type) {
        SessionQueue queue = queues.computeIfAbsent(handle.getWsSessionId(), id -> new SessionQueue());
        boolean shouldSchedule;
        boolean shouldClose;
        int depthAfterEnqueue;
        synchronized (queue) {
            if (conflateKey != null) {
                queue.frames.removeIf(f -> conflateKey.equals(f.conflateKey()));
            }
            if (queue.frames.size() >= queueCapacity) {
                queue.frames.pollFirst();
                meterRegistry.counter("ws_queue_dropped_total").increment();
                queue.consecutiveDrops.incrementAndGet();
            }
            queue.frames.addLast(new Frame(encodedFrame, conflateKey));
            depthAfterEnqueue = queue.frames.size();
            shouldSchedule = queue.draining.compareAndSet(false, true);
            // Never keeps catching up (design doc edge case 32): a session
            // this far behind protects the server, not the client — closing
            // it is what "closes the session" instead of the heap growing.
            shouldClose = queue.consecutiveDrops.get() >= dropCloseThreshold;
        }
        // Backpressure headroom (design doc section 9) — a distribution
        // summary, not a gauge: each enqueue samples that session's depth
        // at that moment, so the shape of the distribution over time is visible.
        meterRegistry.summary("ws_queue_depth").record(depthAfterEnqueue);
        meterRegistry.counter("ws_frames_out_total", "type", type.wireValue()).increment();
        if (shouldClose) {
            closeQuietly(handle);
            return;
        }
        if (shouldSchedule) {
            drainExecutor.submit(() -> drain(handle, queue));
        }
    }

    private void closeQuietly(SessionHandle handle) {
        try {
            handle.getSession().close(SLOW_CONSUMER_CLOSE);
        } catch (IOException e) {
            log.debug("Failed to close a persistently overflowing session={} cleanly.", handle.getWsSessionId(), e);
        }
    }

    public void removeQueue(String wsSessionId) {
        queues.remove(wsSessionId);
    }

    private void drain(SessionHandle handle, SessionQueue queue) {
        while (true) {
            Frame next;
            synchronized (queue) {
                next = queue.frames.pollFirst();
                if (next == null) {
                    queue.draining.set(false);
                    queue.consecutiveDrops.set(0); // fully caught up — this session isn't the slow one (any more)
                    return;
                }
            }
            try {
                handle.getSession().sendMessage(new TextMessage(next.encoded()));
            } catch (IOException e) {
                log.debug("Failed to drain a frame to session={}, treating as a dead connection.", handle.getWsSessionId(), e);
                synchronized (queue) {
                    queue.draining.set(false);
                }
                return;
            }
        }
    }

    private record Frame(String encoded, UUID conflateKey) {
    }

    private static final class SessionQueue {
        private final Deque<Frame> frames = new ArrayDeque<>();
        private final AtomicBoolean draining = new AtomicBoolean(false);
        private final AtomicInteger consecutiveDrops = new AtomicInteger(0);
    }
}
