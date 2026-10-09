package com.tamaspinter.backend.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatMessageValidatorTest {

    @Test
    void validate_withPlainText_acceptsAndTrims() {
        // Given
        String raw = "   hello table  ";

        // When
        ChatMessageValidator.Result result = ChatMessageValidator.validate(raw);

        // Then
        assertEquals(ChatMessageValidator.Status.ACCEPTED, result.status());
        assertEquals("hello table", result.text());
    }

    @Test
    void validate_withBlankOrNull_returnsEmpty() {
        // Given / When / Then
        assertEquals(ChatMessageValidator.Status.EMPTY, ChatMessageValidator.validate("").status());
        assertEquals(ChatMessageValidator.Status.EMPTY, ChatMessageValidator.validate("  \t ").status());
        assertEquals(ChatMessageValidator.Status.EMPTY, ChatMessageValidator.validate(null).status());
    }

    @Test
    void validate_withExactlyMaxLength_accepts() {
        // Given
        String raw = "a".repeat(ChatMessageValidator.MAX_LENGTH);

        // When
        ChatMessageValidator.Result result = ChatMessageValidator.validate(raw);

        // Then
        assertEquals(ChatMessageValidator.Status.ACCEPTED, result.status());
        assertEquals(ChatMessageValidator.MAX_LENGTH, result.text().length());
    }

    @Test
    void validate_withOneOverMaxLength_rejectsAsTooLong() {
        // Given
        String raw = "a".repeat(ChatMessageValidator.MAX_LENGTH + 1);

        // When
        ChatMessageValidator.Result result = ChatMessageValidator.validate(raw);

        // Then
        assertEquals(ChatMessageValidator.Status.TOO_LONG, result.status());
    }

    @Test
    void validate_countsCodePointsNotUtf16Units() {
        // Given — 300 emoji are 600 UTF-16 units but only 300 code points
        String emoji = "😀".repeat(ChatMessageValidator.MAX_LENGTH);

        // When
        ChatMessageValidator.Result result = ChatMessageValidator.validate(emoji);

        // Then
        assertEquals(ChatMessageValidator.Status.ACCEPTED, result.status());
    }
}
