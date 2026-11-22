package com.hila.lemonadeinterview.service;

import com.hila.lemonadeinterview.repository.WordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@Service
public class WordService {

    private static final Logger log = LoggerFactory.getLogger(WordService.class);
    private static final int CHUNK_SIZE = 8192;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final WordRepository repository;
    private final ExecutorService wordJobs;

    public WordService(WordRepository repository, ExecutorService wordJobs) {
        this.repository = repository;
        this.wordJobs = wordJobs;
    }

    public long count(String word) {
        return repository.count(word.toLowerCase(Locale.ROOT));
    }

    /** Starts a background job for the URL. Returns false if it is already processed or in progress. */
    public boolean submit(String url) {
        if (!repository.tryStart(url)) {
            return false;
        }
        wordJobs.submit(() -> runJob(url));
        return true;
    }

    /**
     * Streams the URL's body into a local count map, then merges it in one step.
     * On any failure nothing is merged, the URL is freed for retry, and the reason is logged.
     */
    void runJob(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() / 100 != 2) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                Map<String, Long> counts = WordCounter.count(WordCounter.utf8Reader(body), CHUNK_SIZE);
                repository.complete(url, counts);
                log.info("job done url={} distinctWords={}", url, counts.size());
            }
        } catch (Exception e) {
            repository.fail(url);
            log.warn("job failed url={} reason={}", url, e.toString());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
