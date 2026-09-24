package com.enterprise.admin.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.enterprise.admin.dto.DatabaseHealthResponse;
import com.enterprise.admin.dto.HealthResponse;
import com.enterprise.admin.dto.HealthStatus;
import com.enterprise.admin.service.HealthService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final HealthService healthService;

    @GetMapping
    public HealthResponse health() {
        return healthService.applicationHealth();
    }

    @GetMapping("/db")
    public ResponseEntity<DatabaseHealthResponse> databaseHealth() {
        DatabaseHealthResponse response = healthService.databaseHealth();
        HttpStatus status = response.status() == HealthStatus.UP ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }
}
