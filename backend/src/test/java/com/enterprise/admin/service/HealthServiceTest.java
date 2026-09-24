package com.enterprise.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.enterprise.admin.dto.DatabaseHealthResponse;
import com.enterprise.admin.dto.HealthResponse;
import com.enterprise.admin.dto.HealthStatus;

class HealthServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private DataSource dataSource;
    private HealthService healthService;

    @BeforeEach
    void setUp() {
        dataSource = mock(DataSource.class);
        healthService = new HealthService(dataSource, Clock.fixed(NOW, ZoneOffset.UTC), "enterprise-admin-backend");
    }

    @Test
    void applicationHealthIsUp() {
        HealthResponse response = healthService.applicationHealth();

        assertThat(response.status()).isEqualTo(HealthStatus.UP);
        assertThat(response.service()).isEqualTo("enterprise-admin-backend");
        assertThat(response.timestamp()).isEqualTo(NOW);
    }

    @Test
    void databaseIsUpWhenConnectionIsValid() throws SQLException {
        Connection connection = mock(Connection.class);
        given(dataSource.getConnection()).willReturn(connection);
        given(connection.isValid(anyInt())).willReturn(true);

        DatabaseHealthResponse response = healthService.databaseHealth();

        assertThat(response.status()).isEqualTo(HealthStatus.UP);
        assertThat(response.component()).isEqualTo("database");
        assertThat(response.responseTimeMs()).isNotNegative();
        verify(connection).close();
    }

    @Test
    void databaseIsDownWhenConnectionIsInvalid() throws SQLException {
        Connection connection = mock(Connection.class);
        given(dataSource.getConnection()).willReturn(connection);
        given(connection.isValid(anyInt())).willReturn(false);

        assertThat(healthService.databaseHealth().status()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void databaseIsDownWhenConnectionFails() throws SQLException {
        given(dataSource.getConnection()).willThrow(new SQLException("Access denied for user 'secret-user'"));

        DatabaseHealthResponse response = healthService.databaseHealth();

        assertThat(response.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(response.toString()).doesNotContain("secret-user");
    }
}
