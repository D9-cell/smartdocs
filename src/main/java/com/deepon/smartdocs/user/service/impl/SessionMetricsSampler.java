package com.deepon.smartdocs.user.service.impl;

import com.deepon.smartdocs.user.repository.SessionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * design doc section 12: {@code auth_sessions_active}, "sampled every 60 s".
 * A Micrometer gauge is pull-based — it re-runs its supplier on every scrape
 * — so a query straight against the repository would hit the database once
 * per Prometheus poll. Sampling into an {@link AtomicLong} on a fixed
 * schedule instead means the gauge read itself is free.
 */
@Component
public class SessionMetricsSampler {

    private final SessionRepository sessionRepository;
    private final AtomicLong activeSessions = new AtomicLong();

    public SessionMetricsSampler(SessionRepository sessionRepository, MeterRegistry meterRegistry) {
        this.sessionRepository = sessionRepository;
        meterRegistry.gauge("auth_sessions_active", activeSessions);
    }

    @Scheduled(fixedRate = 60_000)
    void sample() {
        activeSessions.set(sessionRepository.countByRevokedAtIsNull());
    }
}
