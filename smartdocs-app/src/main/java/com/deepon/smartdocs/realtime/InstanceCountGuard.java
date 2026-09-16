package com.deepon.smartdocs.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * design doc section 3, "Deployment shape at Stage 2": {@link RoomRegistry}
 * is an in-memory {@code ConcurrentHashMap} on one node — a second instance
 * doesn't fail loudly, it silently splits rooms and breaks sync. Logged once
 * at startup rather than enforced, since nothing here can see other
 * instances; Stage 9 moves fanout onto a shared log and removes this limit.
 */
@Component
public class InstanceCountGuard {

    private static final Logger log = LoggerFactory.getLogger(InstanceCountGuard.class);

    public InstanceCountGuard(@Value("${smartdocs.instance.count:1}") int instanceCount) {
        if (instanceCount > 1) {
            log.warn("smartdocs.instance.count={} — Stage 2's realtime room registry is in-memory on a single node. " +
                            "Running more than one instance silently splits rooms and breaks real-time sync (see README; Stage 9 removes this limit).",
                    instanceCount);
        }
    }
}
