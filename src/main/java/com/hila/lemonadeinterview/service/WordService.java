package com.hila.lemonadeinterview.service;

import com.hila.lemonadeinterview.repository.WordRepository;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.ExecutorService;

@Service
public class WordService {

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

    void runJob(String url) {
        // Stub: the real download and counting come in slice 3.
    }
}
