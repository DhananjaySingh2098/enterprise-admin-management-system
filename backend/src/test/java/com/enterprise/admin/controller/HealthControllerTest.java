package com.enterprise.admin.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.enterprise.admin.TestWebSecurityImports;
import com.enterprise.admin.dto.DatabaseHealthResponse;
import com.enterprise.admin.dto.HealthResponse;
import com.enterprise.admin.dto.HealthStatus;
import com.enterprise.admin.service.HealthService;

@WebMvcTest(HealthController.class)
@Import(TestWebSecurityImports.class)
class HealthControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthService healthService;

    @Test
    void healthIsPublicAndReportsUp() throws Exception {
        given(healthService.applicationHealth())
                .willReturn(new HealthResponse(HealthStatus.UP, "enterprise-admin-backend", NOW));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("enterprise-admin-backend"))
                .andExpect(jsonPath("$.timestamp").value("2026-01-01T00:00:00Z"));
    }

    @Test
    void databaseHealthReturns200WhenUp() throws Exception {
        given(healthService.databaseHealth())
                .willReturn(new DatabaseHealthResponse(HealthStatus.UP, "database", 3, NOW));

        mockMvc.perform(get("/api/health/db"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.component").value("database"))
                .andExpect(jsonPath("$.url").doesNotExist())
                .andExpect(jsonPath("$.username").doesNotExist());
    }

    @Test
    void databaseHealthReturns503WhenDown() throws Exception {
        given(healthService.databaseHealth())
                .willReturn(new DatabaseHealthResponse(HealthStatus.DOWN, "database", 2000, NOW));

        mockMvc.perform(get("/api/health/db"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }
}
