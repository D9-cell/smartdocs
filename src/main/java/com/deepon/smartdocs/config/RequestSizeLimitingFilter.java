package com.deepon.smartdocs.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Design doc section 6.8: reject oversized bodies at the container level,
 * not just in {@code ContentValidator}. {@code Content-Length} covers the
 * common case cheaply (reject before reading a byte); the counting stream in
 * {@link SizeLimitingHttpServletRequest} covers the chunked case where
 * {@code Content-Length} is absent.
 */
@Component
@Order(2)
public class RequestSizeLimitingFilter extends HttpFilter {

    private final long maxBodyBytes;

    public RequestSizeLimitingFilter(@Value("${smartdocs.request.max-body-bytes:4194304}") long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxBodyBytes) {
            writeTooLarge(response, declaredLength);
            return;
        }
        chain.doFilter(new SizeLimitingHttpServletRequest(request, maxBodyBytes), response);
    }

    private void writeTooLarge(HttpServletResponse response, long declaredLength) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank","title":"Payload Too Large","status":413,\
                "detail":"Request body of %d bytes exceeds the limit of %d bytes.",\
                "code":"CONTENT_TOO_LARGE"}\
                """.formatted(declaredLength, maxBodyBytes));
    }
}
