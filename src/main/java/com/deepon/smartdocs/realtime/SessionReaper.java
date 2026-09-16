package com.deepon.smartdocs.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

/**
 * design doc section 5.2: closes sessions that stopped proving liveness
 * (heartbeat timeout, 4002) or opened and never subscribed to anything, and
 * sweeps empty rooms as a backstop behind {@link RoomRegistry}'s own atomic
 * join/leave bookkeeping.
 */
@Component
public class SessionReaper {

    private static final Logger log = LoggerFactory.getLogger(SessionReaper.class);
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SUBSCRIBE_GRACE = Duration.ofSeconds(60);
    private static final CloseStatus HEARTBEAT_TIMEOUT_CLOSE = new CloseStatus(4002, "Heartbeat timeout");

    private final SessionRegistry sessionRegistry;
    private final RoomRegistry roomRegistry;
    private final Clock clock;

    public SessionReaper(SessionRegistry sessionRegistry, RoomRegistry roomRegistry, Clock clock) {
        this.sessionRegistry = sessionRegistry;
        this.roomRegistry = roomRegistry;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 15_000)
    void reap() {
        long now = clock.millis();
        for (SessionHandle handle : sessionRegistry.all()) {
            if (now - handle.getLastPongAtMillis() > HEARTBEAT_TIMEOUT.toMillis()) {
                closeQuietly(handle, HEARTBEAT_TIMEOUT_CLOSE);
            } else if (handle.getSubscriptions().isEmpty() && now - handle.getConnectedAtMillis() > SUBSCRIBE_GRACE.toMillis()) {
                closeQuietly(handle, CloseStatus.NORMAL);
            }
        }
        // The compute-based join/leave in RoomRegistry already removes an
        // empty room atomically — this is only a net under a bug in that path.
        roomRegistry.sweepEmpty();
    }

    private void closeQuietly(SessionHandle handle, CloseStatus status) {
        try {
            handle.getSession().close(status);
        } catch (IOException e) {
            log.debug("Failed to close stale session={} cleanly.", handle.getWsSessionId(), e);
        }
    }
}
