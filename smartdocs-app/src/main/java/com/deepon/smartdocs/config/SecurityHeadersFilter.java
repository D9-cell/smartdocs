package com.deepon.smartdocs.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Baseline response headers on every request (design doc section 9):
 * {@code nosniff} stops the browser from guessing a content type XSS could
 * exploit, {@code same-origin} keeps the referrer off third-party requests,
 * and a self-only CSP with no {@code unsafe-inline} is the backstop behind
 * the client's own {@code textContent}-only rendering rule. Runs first so
 * these headers land on error responses too, not just successful ones.
 */
@Component
@Order(0)
public class SecurityHeadersFilter extends HttpFilter {

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "same-origin");
        response.setHeader("Content-Security-Policy", "default-src 'self'");
        chain.doFilter(request, response);
    }
}
