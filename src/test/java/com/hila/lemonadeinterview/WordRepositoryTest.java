package com.hila.lemonadeinterview;

import com.hila.lemonadeinterview.repository.WordRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordRepositoryTest {

    @Test
    void tryStart_concurrentSameUrl_onlyOneWins() throws Exception {
        WordRepository repository = new WordRepository();
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return repository.tryStart("http://race.test/same");
            }));
        }
        go.countDown();

        int winners = 0;
        for (Future<Boolean> r : results) {
            if (r.get()) {
                winners++;
            }
        }
        pool.shutdown();
        assertEquals(1, winners);
    }

    @Test
    void tryStart_processedOrInProgress_returnsFalse() {
        WordRepository repository = new WordRepository();

        assertTrue(repository.tryStart("http://a.test/x"));
        assertFalse(repository.tryStart("http://a.test/x"), "in progress");

        repository.complete("http://a.test/x", Map.of("w", 1L));
        assertFalse(repository.tryStart("http://a.test/x"), "processed");

        assertTrue(repository.tryStart("http://a.test/y"));
        repository.fail("http://a.test/y");
        assertTrue(repository.tryStart("http://a.test/y"), "failed URL can be retried");
    }
}
