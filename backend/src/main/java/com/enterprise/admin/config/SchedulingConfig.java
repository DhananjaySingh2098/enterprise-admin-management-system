package com.enterprise.admin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables housekeeping jobs such as expired refresh-token purging. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
