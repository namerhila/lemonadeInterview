package com.hila.lemonadeinterview.repository;

import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory word counts plus processed / in-progress URL sets.
 * All access goes through one lock, so check-and-set is atomic and a merge is visible all at once.
 */
@Repository
public class WordRepository {

    private final Map<String, Long> counts = new HashMap<>();
    private final Set<String> processed = new HashSet<>();
    private final Set<String> inProgress = new HashSet<>();

    public synchronized long count(String word) {
        return counts.getOrDefault(word, 0L);
    }

    public synchronized void addCounts(Map<String, Long> delta) {
        delta.forEach((word, n) -> counts.merge(word, n, Long::sum));
    }

    /** Marks the URL in progress and returns true, unless it is already processed or in progress. */
    public synchronized boolean tryStart(String url) {
        if (processed.contains(url)) {
            return false;
        }
        return inProgress.add(url);
    }

    /** Merges a finished job's counts and moves its URL from in progress to processed. */
    public synchronized void complete(String url, Map<String, Long> delta) {
        addCounts(delta);
        inProgress.remove(url);
        processed.add(url);
    }

    /** Frees a failed job's URL so it can be retried. */
    public synchronized void fail(String url) {
        inProgress.remove(url);
    }
}
