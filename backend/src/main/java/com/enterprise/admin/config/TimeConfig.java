package com.enterprise.admin.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    /** Single UTC clock so timestamps are consistent and tests can pin time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
