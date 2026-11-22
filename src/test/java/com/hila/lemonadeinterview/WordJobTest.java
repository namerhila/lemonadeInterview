package com.hila.lemonadeinterview;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WordJobTest {

    private static final long WAIT_MS = 5000;

    static TestFileServer files;

    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void startServer() throws Exception {
        files = new TestFileServer();
    }

    @AfterAll
    static void stopServer() {
        files.close();
    }

    private ResultActions postUrl(String url) throws Exception {
        return mvc.perform(post("/words/").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + url + "\"}"));
    }

    private long getCount(String encodedWord) throws Exception {
        String json = mvc.perform(get(URI.create("/words/" + encodedWord)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.count")).longValue();
    }

    /** Polls GET until the word reaches the expected count (the job runs in the background). */
    private void awaitCount(String encodedWord, long expected) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        long actual = getCount(encodedWord);
        while (actual != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            actual = getCount(encodedWord);
        }
        assertEquals(expected, actual, "count of " + encodedWord);
    }

    /** Polls POST until the URL is accepted again, i.e. its failed job freed it. */
    private void awaitRetryable(String url) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (postUrl(url).andReturn().getResponse().getStatus() == 202) {
                return;
            }
            Thread.sleep(50);
        }
        fail("URL was never freed for retry: " + url);
    }

    @Test
    void countryTwice_getReturns2() throws Exception {
        postUrl(files.serve("/country.txt", "country country")).andExpect(status().isAccepted());
        awaitCount("country", 2);
    }

    @Test
    void mixedCase_endToEnd() throws Exception {
        postUrl(files.serve("/mixcase.txt", "Mixcase MIXCASE")).andExpect(status().isAccepted());
        awaitCount("MiXcAsE", 2);
    }

    @Test
    void numbersAndRegex_lookupEncoded() throws Exception {
        postUrl(files.serve("/regex.txt", "4242 ^q.*z$")).andExpect(status().isAccepted());
        awaitCount("4242", 1);
        awaitCount("%5Eq.*z%24", 1);
    }

    @Test
    void post_returns202BeforeDownloadFinishes() throws Exception {
        long start = System.currentTimeMillis();
        postUrl(files.url("/slow")).andExpect(status().isAccepted());
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 1000, "POST took " + elapsed + "ms; the /slow download takes 3000ms");
    }

    @Test
    void unreachableOr404_canRetry() throws Exception {
        String unreachable = "http://localhost:1/unreachable";
        postUrl(unreachable).andExpect(status().isAccepted());
        awaitRetryable(unreachable);

        String notFound = files.url("/404");
        postUrl(notFound).andExpect(status().isAccepted());
        awaitRetryable(notFound);
    }

    @Test
    void brokenStream_mergesNothing() throws Exception {
        String broken = files.url("/broken");
        postUrl(broken).andExpect(status().isAccepted());
        awaitRetryable(broken);
        assertEquals(0, getCount("brokenword"));
    }

    @Test
    void concurrentDifferentUrls_noLostCounts() throws Exception {
        String body = "raceword ".repeat(1000);
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            urls.add(files.serve("/race" + i + ".txt", body));
        }

        ExecutorService clients = Executors.newFixedThreadPool(10);
        List<Future<Integer>> statuses = new ArrayList<>();
        for (String url : urls) {
            statuses.add(clients.submit(() -> postUrl(url).andReturn().getResponse().getStatus()));
        }
        for (Future<Integer> s : statuses) {
            assertEquals(202, s.get());
        }
        clients.shutdown();

        awaitCount("raceword", 10_000);
    }

    @Test
    void hebrewFile_202_countsUnchanged_canRetry() throws Exception {
        String url = files.serve("/hebrew.txt", "qqheb " + "שלום עולם ".repeat(100));
        postUrl(url).andExpect(status().isAccepted());
        awaitRetryable(url);
        assertEquals(0, getCount("qqheb"));
    }

    @Test
    void binaryFile_202_countsUnchanged_canRetry() throws Exception {
        byte[] noise = new byte[4000];
        new Random(42).nextBytes(noise);
        noise[10] = (byte) 0xFF; // never valid in UTF-8
        byte[] prefix = "qqbin ".getBytes(UTF_8);
        byte[] body = new byte[prefix.length + noise.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(noise, 0, body, prefix.length, noise.length);

        String url = files.serve("/binary.dat", body);
        postUrl(url).andExpect(status().isAccepted());
        awaitRetryable(url);
        assertEquals(0, getCount("qqbin"));
    }

    @Test
    void accentedFile_counted() throws Exception {
        // 1 accented letter among ~800 letters: well under the 1% limit
        String text = "the quick brown fox jumps ".repeat(40) + "the café is nice";
        postUrl(files.serve("/accented.txt", text)).andExpect(status().isAccepted());
        awaitCount("caf%C3%A9", 1);
    }
}
