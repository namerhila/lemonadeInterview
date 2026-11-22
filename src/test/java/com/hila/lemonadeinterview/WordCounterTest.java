package com.hila.lemonadeinterview;

import com.hila.lemonadeinterview.service.NotEnglishException;
import com.hila.lemonadeinterview.service.WordCounter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WordCounterTest {

    private static Map<String, Long> count(String text) throws IOException {
        return WordCounter.count(new StringReader(text), 8192);
    }

    @Test
    void dashAndCommaSplit() throws IOException {
        assertEquals(Map.of("count", 1L, "ry", 1L, "a", 1L, "b", 1L), count("Count-ry a,b"));
    }

    @Test
    void lowercases() throws IOException {
        assertEquals(Map.of("country", 2L), count("Country COUNTRY"));
    }

    @Test
    void keepsNumbersRegexPunctuation() throws IOException {
        assertEquals(
                Map.of("42", 1L, "3.14", 1L, "^a.*b$", 1L, "\\d+", 1L, "country.", 1L, "country", 1L),
                count("42 3.14 ^a.*b$ \\d+ country. country"));
    }

    @Test
    void dropsEmptyTokens() throws IOException {
        assertEquals(Map.of("a", 1L, "b", 1L), count("a--b ,,"));
    }

    @Test
    void crlfAndTabsAreWhitespace() throws IOException {
        assertEquals(Map.of("a", 1L, "b", 1L, "c", 1L), count("a\r\nb\tc"));
    }

    @Test
    void wordAcrossChunkBoundary() throws IOException {
        assertEquals(Map.of("country", 2L), WordCounter.count(new StringReader("country country"), 3));
    }

    @Test
    void lastWordWithoutTrailingNewline() throws IOException {
        assertEquals(Map.of("x", 1L, "y", 1L), count("x y"));
    }

    @Test
    void emptyOrSeparatorsOnly_returnsEmptyMap() throws IOException {
        assertEquals(Map.of(), count(""));
        assertEquals(Map.of(), count(" -,\n"));
    }

    @Test
    void hebrewText_throwsNotEnglish() {
        assertThrows(NotEnglishException.class, () -> count("שלום עולם זה טקסט"));
    }

    @Test
    void russianText_throwsNotEnglish() {
        assertThrows(NotEnglishException.class, () -> count("Привет мир это текст"));
    }

    @Test
    void fewAccentedWords_accepted() throws IOException {
        Map<String, Long> counts = count("the quick brown fox ".repeat(50) + "café naïve");
        assertEquals(1L, counts.get("café"));
        assertEquals(50L, counts.get("fox"));
    }

    @Test
    void noLettersAtAll_accepted() throws IOException {
        assertEquals(Map.of("42", 1L, "3.14", 1L, "^$", 1L, "[]", 1L), count("42 3.14 ^$ []"));
    }

    @Test
    void exactlyOnePercent_accepted() throws IOException {
        assertEquals(1, count("a".repeat(99) + "é").size());
    }

    @Test
    void overOnePercent_rejected() {
        assertThrows(NotEnglishException.class, () -> count("a".repeat(98) + "éé"));
    }

    @Test
    void invalidUtf8_throws() {
        byte[] bytes = {(byte) 0xFF, (byte) 0xFE, 0x00, (byte) 0xC3};
        assertThrows(CharacterCodingException.class,
                () -> WordCounter.count(WordCounter.utf8Reader(new ByteArrayInputStream(bytes)), 8192));
    }
}
