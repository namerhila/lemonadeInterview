package com.hila.lemonadeinterview.repository;

import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory word counts. All access goes through one lock so a merge is visible all at once.
 */
@Repository
public class WordRepository {

    private final Map<String, Long> counts = new HashMap<>();

    public synchronized long count(String word) {
        return counts.getOrDefault(word, 0L);
    }

    public synchronized void addCounts(Map<String, Long> delta) {
        delta.forEach((word, n) -> counts.merge(word, n, Long::sum));
    }
}
