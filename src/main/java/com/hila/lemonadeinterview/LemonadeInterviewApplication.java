package com.hila.lemonadeinterview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootApplication
public class LemonadeInterviewApplication {

    private static final int WORKERS = 4;

    public static void main(String[] args) {
        SpringApplication.run(LemonadeInterviewApplication.class, args);
    }

    /** Bounded pool for background word-count jobs. */
    @Bean
    public ExecutorService wordJobs() {
        return Executors.newFixedThreadPool(WORKERS);
    }

}
