package com.deepon.smartdocs.document.controller;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.common.ActorArgumentResolver;
import com.deepon.smartdocs.common.web.EtagSupport;
import com.deepon.smartdocs.config.WebConfig;
import com.deepon.smartdocs.document.entity.Document;
import com.deepon.smartdocs.document.exception.ContentHashMismatchException;
import com.deepon.smartdocs.document.exception.ContentTooLargeException;
import com.deepon.smartdocs.document.exception.DocumentNotFoundException;
import com.deepon.smartdocs.document.exception.InvalidContentException;
import com.deepon.smartdocs.document.exception.VersionMismatchException;
import com.deepon.smartdocs.document.service.DocumentService;
import com.deepon.smartdocs.security.CookieSupport;
import com.deepon.smartdocs.security.SessionAuthFilter;
import com.deepon.smartdocs.user.service.SessionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Design doc section 6.7: every status code in the error catalogue, asserted
 * against a mocked service. {@code SessionAuthFilter}'s own dependencies
 * ({@code SessionService}, {@code CookieSupport}) are mocked purely so the
 * slice's bean graph resolves — {@code Actor} is injected directly as a
 * request attribute per call ({@link #asUser}), bypassing the filter's
 * actual cookie-reading behaviour, which is exercised at the integration level.
 */
@WebMvcTest(DocumentController.class)
@Import({EtagSupport.class, WebConfig.class, ActorArgumentResolver.class, SimpleMeterRegistry.class})
class DocumentControllerWebMvcTest {

    private static final Actor ACTOR = Actor.human(UUID.fromString("11111111-1111-1111-1111-111111111111"));

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentService documentService;

    @MockBean
    private SessionService sessionService;

    @MockBean
    private CookieSupport cookieSupport;

    private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder builder) {
        return builder.requestAttr(SessionAuthFilter.ACTOR_ATTRIBUTE, ACTOR);
    }

    private Document sampleDocument(long version) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Document(UUID.fromString("0f8d3b2a-6c4e-4a91-9d7f-2b5c8e1a3d40"), ACTOR.userId(), "Notes", "hello",
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", 5, version,
                "anonymous", "anonymous", now, now);
    }

    @Test
    void createReturns201WithLocationAndETag() throws Exception {
        when(documentService.create(eq(ACTOR), any(), any())).thenReturn(sampleDocument(1));

        mockMvc.perform(asUser(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Notes\",\"content\":\"hello\"}")))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void getReturns200WithETagAndNoStore() throws Exception {
        UUID id = UUID.fromString("0f8d3b2a-6c4e-4a91-9d7f-2b5c8e1a3d40");
        when(documentService.get(ACTOR, id)).thenReturn(sampleDocument(7));

        mockMvc.perform(asUser(get("/api/v1/documents/{id}", id)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"7\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.version").value(7));
    }

    @Test
    void getUnknownDocumentReturns404WithDocumentNotFoundCode() throws Exception {
        UUID id = UUID.randomUUID();
        when(documentService.get(ACTOR, id)).thenThrow(new DocumentNotFoundException(id));

        mockMvc.perform(asUser(get("/api/v1/documents/{id}", id)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void malformedUuidPathReturns400WithInvalidDocumentIdCode() throws Exception {
        mockMvc.perform(asUser(get("/api/v1/documents/{id}", "not-a-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT_ID"));
    }

    @Test
    void noSessionOnAProtectedRouteReturns401SessionInvalid() throws Exception {
        // No asUser(...) here: this is exactly what SessionAuthFilter leaves
        // behind for an unauthenticated request — the resolver, not the
        // filter, is what turns that into 401 for a route that needs an Actor.
        mockMvc.perform(get("/api/v1/documents/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
    }

    @Test
    void updateContentWithoutIfMatchReturns428() throws Exception {
        mockMvc.perform(asUser(put("/api/v1/documents/{id}/content", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi\"}")))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
    }

    @Test
    void updateContentWithMalformedIfMatchReturns400() throws Exception {
        mockMvc.perform(asUser(put("/api/v1/documents/{id}/content", UUID.randomUUID())
                        .header("If-Match", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_IF_MATCH"));
    }

    @Test
    void updateContentSuccessReturns200WithNewETagAndNoContentField() throws Exception {
        UUID id = UUID.randomUUID();
        when(documentService.updateContent(eq(ACTOR), eq(id), eq(7L), anyString(), any())).thenReturn(sampleDocument(8));

        mockMvc.perform(asUser(put("/api/v1/documents/{id}/content", id)
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}")))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"8\""))
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    @Test
    void updateContentVersionMismatchReturns412WithCurrentState() throws Exception {
        UUID id = UUID.randomUUID();
        when(documentService.updateContent(eq(ACTOR), eq(id), eq(7L), anyString(), any()))
                .thenThrow(new VersionMismatchException(7L, 8L, "current text", "hash8", 12));

        mockMvc.perform(asUser(put("/api/v1/documents/{id}/content", id)
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}")))
                .andExpect(status().is(412))
                .andExpect(header().string("ETag", "\"8\""))
                .andExpect(jsonPath("$.code").value("VERSION_MISMATCH"))
                .andExpect(jsonPath("$.expectedVersion").value(7))
                .andExpect(jsonPath("$.currentVersion").value(8))
                .andExpect(jsonPath("$.currentContent").value("current text"));
    }

    @Test
    void updateContentHashMismatchReturns400() throws Exception {
        UUID id = UUID.randomUUID();
        when(documentService.updateContent(eq(ACTOR), eq(id), eq(7L), anyString(), any()))
                .thenThrow(new ContentHashMismatchException("deadbeef", "cafebabe"));

        mockMvc.perform(asUser(put("/api/v1/documents/{id}/content", id)
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\",\"clientHash\":\"deadbeef\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONTENT_HASH_MISMATCH"));
    }

    @Test
    void updateContentTooLargeReturns413() throws Exception {
        UUID id = UUID.randomUUID();
        when(documentService.updateContent(eq(ACTOR), eq(id), eq(7L), anyString(), any()))
                .thenThrow(new ContentTooLargeException(2_000_000, 1_048_576));

        mockMvc.perform(asUser(put("/api/v1/documents/{id}/content", id)
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}")))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("CONTENT_TOO_LARGE"));
    }

    @Test
    void updateContentInvalidContentReturns422() throws Exception {
        UUID id = UUID.randomUUID();
        when(documentService.updateContent(eq(ACTOR), eq(id), eq(7L), anyString(), any()))
                .thenThrow(new InvalidContentException("INVALID_CONTENT_NUL", "content", "contains NUL"));

        mockMvc.perform(asUser(put("/api/v1/documents/{id}/content", id)
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"bad\"}")))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INVALID_CONTENT_NUL"));
    }

    @Test
    void malformedJsonBodyReturns400() throws Exception {
        mockMvc.perform(asUser(put("/api/v1/documents/{id}/content", UUID.randomUUID())
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not valid json")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    @Test
    void unknownJsonFieldReturns400MalformedJson() throws Exception {
        mockMvc.perform(asUser(put("/api/v1/documents/{id}/content", UUID.randomUUID())
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi\",\"bogusField\":true}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    @Test
    void unsupportedMediaTypeReturns415() throws Exception {
        mockMvc.perform(asUser(put("/api/v1/documents/{id}/content", UUID.randomUUID())
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void wrongVerbOnKnownPathReturns405() throws Exception {
        // The collection path only maps GET and POST; DELETE on it has no handler.
        mockMvc.perform(delete("/api/v1/documents"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void renameSuccessReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(documentService.rename(eq(ACTOR), eq(id), eq(3L), anyString())).thenReturn(sampleDocument(4));

        mockMvc.perform(asUser(patch("/api/v1/documents/{id}", id)
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed\"}")))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""));
    }

    @Test
    void deleteWithoutIfMatchReturns428() throws Exception {
        mockMvc.perform(asUser(delete("/api/v1/documents/{id}", UUID.randomUUID())))
                .andExpect(status().is(428));
    }

    @Test
    void deleteSuccessReturns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(asUser(delete("/api/v1/documents/{id}", id).header("If-Match", "\"3\"")))
                .andExpect(status().isNoContent());
    }

    @Test
    void everyErrorResponseCarriesRequestIdHeader() throws Exception {
        UUID id = UUID.randomUUID();
        when(documentService.get(ACTOR, id)).thenThrow(new DocumentNotFoundException(id));

        // RequestIdFilter is not loaded under @WebMvcTest (it's a plain
        // @Component, not scanned), so this only proves the handler doesn't
        // choke on a missing MDC value. The header presence itself is
        // exercised by the full-stack integration test.
        mockMvc.perform(asUser(get("/api/v1/documents/{id}", id)))
                .andExpect(status().isNotFound());
    }
}
