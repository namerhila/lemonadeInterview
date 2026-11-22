package com.hila.lemonadeinterview;

import com.hila.lemonadeinterview.repository.WordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.URLEncoder;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WordGetTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    WordRepository repository;

    @Test
    void unknownWord_returnsZero() throws Exception {
        mvc.perform(get(URI.create("/words/zzqq")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.word").value("zzqq"))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void lookupIsCaseInsensitive() throws Exception {
        repository.addCounts(Map.of("casecheck", 2L));

        mvc.perform(get(URI.create("/words/CASECHECK")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.word").value("casecheck"))
                .andExpect(jsonPath("$.count").value(2));
        mvc.perform(get(URI.create("/words/casecheck")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void encodedRegexWord_isDecoded() throws Exception {
        repository.addCounts(Map.of("^a.*b$", 1L));

        mvc.perform(get(URI.create("/words/%5Ea.*b%24")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.word").value("^a.*b$"))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void word30Bytes_ok() throws Exception {
        mvc.perform(get(URI.create("/words/" + "a".repeat(30))))
                .andExpect(status().isOk());
    }

    @Test
    void word31Bytes_returns400() throws Exception {
        mvc.perform(get(URI.create("/words/" + "a".repeat(31))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        String hebrew16Chars32Bytes = "שלום".repeat(4);
        mvc.perform(get(URI.create("/words/" + URLEncoder.encode(hebrew16Chars32Bytes, UTF_8))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void emptyWord_returns400() throws Exception {
        mvc.perform(get(URI.create("/words/")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a-b", "a,b", "a%20b", "a%09b"})
    void wordWithSeparator_returns400(String rawWord) throws Exception {
        mvc.perform(get(URI.create("/words/" + rawWord)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
