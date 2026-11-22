package com.hila.lemonadeinterview.controller;

import com.hila.lemonadeinterview.error.BadRequestException;
import com.hila.lemonadeinterview.service.WordService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
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

    public record UrlRequest(String url) {
    }

    public record SubmitResult(String status, String url) {
    }

    @PostMapping("/words/")
    public ResponseEntity<SubmitResult> submit(@RequestBody UrlRequest request) {
        String url = validUrl(request.url());
        if (wordService.submit(url)) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new SubmitResult("accepted", url));
        }
        return ResponseEntity.ok(new SubmitResult("skipped", url));
    }

    /** Returns the trimmed URL, or throws if it is missing, malformed, or not http/https. */
    private static String validUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("url is required");
        }
        String url = raw.trim();
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new BadRequestException("url is malformed");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new BadRequestException("url must use http or https");
        }
        if (uri.getHost() == null) {
            throw new BadRequestException("url has no host");
        }
        return url;
    }
}
