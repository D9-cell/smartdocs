package com.deepon.smartdocs.document;

import com.deepon.smartdocs.document.exception.CursorInvalidException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    private final CursorCodec codec = new CursorCodec();

    @Test
    void roundTripsUpdatedAtAndId() {
        Instant updatedAt = Instant.parse("2026-03-01T12:34:56.789012Z");
        UUID id = UUID.randomUUID();

        String cursor = codec.encode(updatedAt, id);
        CursorCodec.Cursor decoded = codec.decode(cursor);

        assertThat(decoded.updatedAt()).isEqualTo(updatedAt);
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    void garbageCursorThrowsCursorInvalidNotAStackTrace() {
        assertThatThrownBy(() -> codec.decode("not-a-real-cursor!!"))
                .isInstanceOf(CursorInvalidException.class);
    }

    @Test
    void validBase64WithoutASeparatorThrowsCursorInvalid() {
        String noSeparator = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("nosep".getBytes());
        assertThatThrownBy(() -> codec.decode(noSeparator))
                .isInstanceOf(CursorInvalidException.class);
    }

    @Test
    void malformedIdPortionThrowsCursorInvalid() {
        String plain = "123456789|not-a-uuid";
        String cursor = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(plain.getBytes());
        assertThatThrownBy(() -> codec.decode(cursor))
                .isInstanceOf(CursorInvalidException.class);
    }
}
