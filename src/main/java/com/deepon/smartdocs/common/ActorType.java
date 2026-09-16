package com.deepon.smartdocs.common;

/**
 * {@code AGENT} and {@code SYSTEM} have no row in {@code app_user} — only
 * {@code HUMAN} does. Stage 3 introduces agent-attributed work; this stage
 * only ever constructs {@code HUMAN} and {@link Actor#SYSTEM}.
 */
public enum ActorType {
    HUMAN,
    AGENT,
    SYSTEM
}
