package com.hila.lemonadeinterview.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Streams text in fixed-size chunks and counts words into a local map.
 * '-', ',' and whitespace separate words; every other character is part of a word.
 * The unfinished word at the end of a chunk is carried into the next chunk.
 * Rejects the text if more than 1% of its letters are not a-z/A-Z.
 */
public final class WordCounter {

    private static final long EARLY_CHECK_LETTERS = 10_000;

    private WordCounter() {
    }

    /** A UTF-8 reader that throws CharacterCodingException on invalid bytes instead of replacing them. */
    public static Reader utf8Reader(InputStream in) {
        return new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT));
    }

    public static Map<String, Long> count(Reader reader, int chunkSize) throws IOException {
        Map<String, Long> counts = new HashMap<>();
        StringBuilder word = new StringBuilder();
        char[] chunk = new char[chunkSize];
        long letters = 0;
        long nonLatin = 0;
        int n;
        while ((n = reader.read(chunk)) != -1) {
            for (int i = 0; i < n; i++) {
                char c = chunk[i];
                if (Character.isLetter(c)) {
                    letters++;
                    if (!isLatin(c)) {
                        nonLatin++;
                    }
                }
                if (c == '-' || c == ',' || Character.isWhitespace(c)) {
                    addWord(word, counts);
                } else {
                    word.append(c);
                }
            }
            if (letters >= EARLY_CHECK_LETTERS && isNotEnglish(nonLatin, letters)) {
                throw new NotEnglishException(nonLatin, letters);
            }
        }
        if (isNotEnglish(nonLatin, letters)) {
            throw new NotEnglishException(nonLatin, letters);
        }
        addWord(word, counts); // last word, when the text has no trailing separator
        return counts;
    }

    private static boolean isLatin(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** More than 1% non-Latin letters. No letters at all counts as English. */
    private static boolean isNotEnglish(long nonLatin, long letters) {
        return nonLatin * 100 > letters;
    }

    private static void addWord(StringBuilder word, Map<String, Long> counts) {
        if (!word.isEmpty()) {
            counts.merge(word.toString().toLowerCase(Locale.ROOT), 1L, Long::sum);
            word.setLength(0);
        }
    }
}
