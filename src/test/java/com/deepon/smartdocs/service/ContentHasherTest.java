package com.deepon.smartdocs.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentHasherTest {

    private final ContentHasher hasher = new ContentHasher();

    @Test
    void hashesEmptyStringToTheWellKnownSha256Value() {
        assertThat(hasher.hash("")).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void hashIsDeterministic() {
        String content = "hello\nworld\n";
        assertThat(hasher.hash(content)).isEqualTo(hasher.hash(content));
    }

    @Test
    void hashIsSixtyFourLowercaseHexChars() {
        String hash = hasher.hash("some content");
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void differentContentProducesDifferentHash() {
        assertThat(hasher.hash("a")).isNotEqualTo(hasher.hash("b"));
    }

    @Test
    void utf8SizeCountsBytesNotJavaCharLength() {
        // A single emoji is one Java "char length" of 2 (a surrogate pair) and 4 UTF-8 bytes.
        String emoji = "😀"; // 😀
        assertThat(hasher.utf8SizeBytes(emoji)).isEqualTo(4);
    }

    @Test
    void utf8SizeOfEmptyStringIsZero() {
        assertThat(hasher.utf8SizeBytes("")).isEqualTo(0);
    }
}
