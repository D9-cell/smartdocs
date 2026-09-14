package com.deepon.statecore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * A single injectable {@link Clock}, UTC. Every timestamp in the system
 * flows through this bean so tests can substitute a fixed clock instead of
 * depending on wall-clock time.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
