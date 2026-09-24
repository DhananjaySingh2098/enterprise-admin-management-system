package com.enterprise.testsupport;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/** Test-only endpoints that raise specific errors. Lives outside com.enterprise.admin so it is never scanned. */
@RestController
public class ExceptionProbeController {

    @GetMapping("/probe/boom")
    String boom() {
        throw new IllegalStateException("jdbc:mysql://internal-host:3306 password=hunter2");
    }

    @PostMapping("/probe/validate")
    String validate(@Valid @RequestBody ProbeRequest request) {
        return request.name();
    }

    public record ProbeRequest(@NotBlank String name) {
    }
}
