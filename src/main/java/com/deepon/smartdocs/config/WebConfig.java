package com.deepon.smartdocs.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Static resource mapping needs no explicit configuration: Spring Boot
 * already serves {@code src/main/resources/static} at {@code /}, and the API
 * lives under {@code /api/v1} so there is no collision (design doc section
 * 3.2 — one origin, no CORS problem in Stage 0). Request body size limits
 * are enforced by {@link RequestSizeLimitingFilter}, not here, because a
 * filter runs before Spring MVC's resource handling and can reject a body
 * before any bytes are buffered.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
}
