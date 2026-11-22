package com.hila.lemonadeinterview.service;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Streams text in fixed-size chunks and counts words into a local map.
 * '-', ',' and whitespace separate words; every other character is part of a word.
 * The unfinished word at the end of a chunk is carried into the next chunk.
 */
public final class WordCounter {

    private WordCounter() {
    }

    public static Map<String, Long> count(Reader reader, int chunkSize) throws IOException {
        Map<String, Long> counts = new HashMap<>();
        StringBuilder word = new StringBuilder();
        char[] chunk = new char[chunkSize];
        int n;
        while ((n = reader.read(chunk)) != -1) {
            for (int i = 0; i < n; i++) {
                char c = chunk[i];
                if (c == '-' || c == ',' || Character.isWhitespace(c)) {
                    addWord(word, counts);
                } else {
                    word.append(c);
                }
            }
        }
        addWord(word, counts); // last word, when the text has no trailing separator
        return counts;
    }

    private static void addWord(StringBuilder word, Map<String, Long> counts) {
        if (!word.isEmpty()) {
            counts.merge(word.toString().toLowerCase(Locale.ROOT), 1L, Long::sum);
            word.setLength(0);
        }
    }
}
