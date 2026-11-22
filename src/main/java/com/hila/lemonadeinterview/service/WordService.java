package com.hila.lemonadeinterview.service;

import com.hila.lemonadeinterview.repository.WordRepository;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class WordService {

    private final WordRepository repository;

    public WordService(WordRepository repository) {
        this.repository = repository;
    }

    public long count(String word) {
        return repository.count(word.toLowerCase(Locale.ROOT));
    }
}
