package com.enterprise.admin.security;

import java.io.IOException;
import java.time.Clock;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.enterprise.admin.dto.ApiErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** Writes {@link ApiErrorResponse} bodies from the security filter chain, which runs before MVC advice. */
@Component
@RequiredArgsConstructor
public class ApiErrorWriter {

    private final JsonMapper jsonMapper;
    private final Clock clock;

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        write(request, response, status, message, null);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message,
                      String code) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(),
                ApiErrorResponse.of(clock.instant(), status, message, request.getRequestURI(), code, null));
    }
}
