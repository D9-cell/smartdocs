package com.deepon.smartdocs.realtime;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * design doc section 9: {@code ws.sessions.active} / {@code ws.rooms.active}
 * — "a room count rising without a session count rising is a leak. That
 * single pair of gauges catches the most likely Stage 2 bug." Sampled on a
 * schedule rather than queried live on every Prometheus scrape, same
 * reasoning as {@code SessionMetricsSampler} from Stage 1.
 */
@Component
public class RealtimeMetricsSampler {

    private final SessionRegistry sessionRegistry;
    private final RoomRegistry roomRegistry;
    private final AtomicLong activeSessions = new AtomicLong();
    private final AtomicLong activeRooms = new AtomicLong();

    public RealtimeMetricsSampler(SessionRegistry sessionRegistry, RoomRegistry roomRegistry, MeterRegistry meterRegistry) {
        this.sessionRegistry = sessionRegistry;
        this.roomRegistry = roomRegistry;
        meterRegistry.gauge("ws_sessions_active", activeSessions);
        meterRegistry.gauge("ws_rooms_active", activeRooms);
    }

    @Scheduled(fixedRate = 15_000)
    void sample() {
        activeSessions.set(sessionRegistry.countActive());
        activeRooms.set(roomRegistry.countActiveRooms());
    }
}
