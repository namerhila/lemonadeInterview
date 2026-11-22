package com.hila.lemonadeinterview.controller;

import com.hila.lemonadeinterview.error.BadRequestException;
import com.hila.lemonadeinterview.service.WordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

@RestController
public class WordController {

    private static final int MAX_WORD_BYTES = 30;
    private static final Pattern UNSTORABLE = Pattern.compile("[\\s,\\-]");

    private final WordService wordService;

    public WordController(WordService wordService) {
        this.wordService = wordService;
    }

    public record WordCount(String word, long count) {
    }

    @GetMapping("/words/{word}")
    public WordCount getCount(@PathVariable String word) {
        if (word.isEmpty()) {
            throw new BadRequestException("word is empty");
        }
        if (word.getBytes(StandardCharsets.UTF_8).length > MAX_WORD_BYTES) {
            throw new BadRequestException("word is longer than " + MAX_WORD_BYTES + " bytes");
        }
        if (UNSTORABLE.matcher(word).find()) {
            throw new BadRequestException("word must not contain whitespace, '-' or ','");
        }
        return new WordCount(word.toLowerCase(Locale.ROOT), wordService.count(word));
    }

    @GetMapping("/words/")
    public WordCount getEmptyWord() {
        throw new BadRequestException("word is empty");
    }
}
