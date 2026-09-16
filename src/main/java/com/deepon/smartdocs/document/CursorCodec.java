package com.deepon.smartdocs.document;

import com.deepon.smartdocs.document.exception.CursorInvalidException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * base64url of {@code updatedAtMicros|id} (design doc section 7.2). At this
 * stage the cursor carries only public values already scoped to the
 * caller's own rows, so a plain encoding is enough — a malformed one is
 * always 400, never a stack trace.
 */
@Component
public class CursorCodec {

    public record Cursor(Instant updatedAt, UUID id) {
    }

    public String encode(Instant updatedAt, UUID id) {
        long micros = updatedAt.getEpochSecond() * 1_000_000L + updatedAt.getNano() / 1_000L;
        String plain = micros + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    public Cursor decode(String cursor) {
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = plain.indexOf('|');
            if (sep < 0) {
                throw new CursorInvalidException("Malformed cursor.");
            }
            long micros = Long.parseLong(plain.substring(0, sep));
            UUID id = UUID.fromString(plain.substring(sep + 1));
            Instant updatedAt = Instant.ofEpochSecond(micros / 1_000_000L, (micros % 1_000_000L) * 1_000L);
            return new Cursor(updatedAt, id);
        } catch (IllegalArgumentException e) {
            throw new CursorInvalidException("Malformed cursor.");
        }
    }
}
