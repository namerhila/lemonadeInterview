package com.hila.lemonadeinterview;

import com.hila.lemonadeinterview.repository.WordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WordPostTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    WordRepository repository;

    private ResultActions postBody(String json) throws Exception {
        return mvc.perform(post("/words/").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void validUrl_returns202() throws Exception {
        postBody("{\"url\":\"http://x.test/a\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.url").value("http://x.test/a"));
    }

    @Test
    void inProgressUrl_returns200Skipped() throws Exception {
        repository.tryStart("http://x.test/inprogress");

        postBody("{\"url\":\"http://x.test/inprogress\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("skipped"))
                .andExpect(jsonPath("$.url").value("http://x.test/inprogress"));
    }

    @Test
    void processedUrl_returns200Skipped_countsUnchanged() throws Exception {
        repository.tryStart("http://x.test/processed");
        repository.complete("http://x.test/processed", Map.of("postprocessedword", 1L));

        postBody("{\"url\":\"http://x.test/processed\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("skipped"));
        mvc.perform(get(URI.create("/words/postprocessedword")))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void urlIsTrimmed() throws Exception {
        repository.tryStart("http://x.test/t");

        postBody("{\"url\":\"  http://x.test/t  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("skipped"))
                .andExpect(jsonPath("$.url").value("http://x.test/t"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "{}",
            "{\"url\":\"\"}",
            "{\"url\":\"   \"}",
            "{\"url\":\"not a url\"}",
            "{\"url\":\"ftp://x.test/a\"}",
            "{\"url\":\"http://\"}"
    })
    void invalidBody_returns400(String body) throws Exception {
        postBody(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
