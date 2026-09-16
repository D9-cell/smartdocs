package com.deepon.smartdocs.websocket.service.impl;

import com.deepon.smartdocs.websocket.repository.WsTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * design doc section 7.2: "unbounded growth of a table nothing reads is a
 * slow leak, not a fast one, which makes it easy to miss." Plaintext tokens
 * never land here — only the hash and its bookkeeping columns — so this is
 * pure housekeeping, not a security control.
 */
@Component
public class WsTicketCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(WsTicketCleanupJob.class);
    private static final Duration RETENTION_AFTER_EXPIRY = Duration.ofHours(1);

    private final WsTicketRepository wsTicketRepository;
    private final Clock clock;

    public WsTicketCleanupJob(WsTicketRepository wsTicketRepository, Clock clock) {
        this.wsTicketRepository = wsTicketRepository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${smartdocs.websocket.ticket-cleanup-interval-ms:600000}")
    @Transactional
    public void run() {
        int deleted = wsTicketRepository.deleteExpiredBefore(clock.instant().minus(RETENTION_AFTER_EXPIRY));
        if (deleted > 0) {
            log.info("WS ticket cleanup: removed {} expired tickets.", deleted);
        }
    }
}
