package com.hila.lemonadeinterview;

import com.hila.lemonadeinterview.service.WordCounter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
