package com.enterprise.admin.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.enterprise.admin.dto.DatabaseHealthResponse;
import com.enterprise.admin.dto.HealthResponse;
import com.enterprise.admin.dto.HealthStatus;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class HealthService {

    static final String DATABASE_COMPONENT = "database";
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;
    private final Clock clock;
    private final String serviceName;

    public HealthService(DataSource dataSource, Clock clock, @Value("${spring.application.name}") String serviceName) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.serviceName = serviceName;
    }

    public HealthResponse applicationHealth() {
        return new HealthResponse(HealthStatus.UP, serviceName, clock.instant());
    }

    public DatabaseHealthResponse databaseHealth() {
        long start = System.nanoTime();
        HealthStatus status;
        try (Connection connection = dataSource.getConnection()) {
            status = connection.isValid(VALIDATION_TIMEOUT_SECONDS) ? HealthStatus.UP : HealthStatus.DOWN;
        } catch (SQLException | RuntimeException ex) {
            // Details stay in server logs only; the response never carries driver or connection information.
            log.warn("Database health check failed: {}", ex.getClass().getSimpleName());
            status = HealthStatus.DOWN;
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        return new DatabaseHealthResponse(status, DATABASE_COMPONENT, elapsedMs, clock.instant());
    }
}
