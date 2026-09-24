package com.enterprise.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.enterprise.admin.entity.EmployeeStatus;

import tools.jackson.databind.json.JsonMapper;

class AuditDetailsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void secretLookingKeysAreDroppedAtAnyDepth() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("newPassword", "Hunter2-Hunter2");
        nested.put("refreshToken", "abc");
        nested.put("ok", "kept");
        String json = AuditDetails.of("password", "p").and("passwordHash", "$2a$12$x").and("jwtSecret", "s")
                .and("Authorization", "Bearer x").and("cookie", "c").and("apiKey", "k").and("credentials", "c")
                .and("reason", "INVALID_CREDENTIALS").and("nested", nested).toJson(mapper);

        assertThat(json).isEqualTo("{\"reason\":\"INVALID_CREDENTIALS\",\"nested\":{\"ok\":\"kept\"}}");
    }

    @Test
    void jwtAndBearerLookingValuesAreMasked() {
        // Fabricated, unsigned JWT-shaped fixture ({"sub":"1"} + the text "signature-value"): not a real token.
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJlLXZhbHVl";
        String json = AuditDetails.of("note", jwt).and("header", "Bearer abc.def").and("other", "basic dXNlcjpwYXNz")
                .and("list", List.of("fine", "Bearer zzz")).toJson(mapper);

        assertThat(json).doesNotContain("eyJ").doesNotContain("abc.def").doesNotContain("dXNlcjpwYXNz").doesNotContain("zzz");
        assertThat(json).contains("\"note\":\"[REDACTED]\"", "\"fine\"");
    }

    @Test
    void valuesAreCleanedAndBounded() {
        String json = AuditDetails.of("text", "line1\nline2\u0000" + "x".repeat(500))
                .and("status", EmployeeStatus.ON_LEAVE)
                .and("many", IntStream.range(0, 50).boxed().toList())
                .and("skipped", null).and("empty", List.of()).toJson(mapper);

        assertThat(json).doesNotContain("\\n").doesNotContain("\\u0000").contains("\"status\":\"ON_LEAVE\"");
        assertThat(json).doesNotContain("skipped").doesNotContain("empty");
        assertThat(mapper.readTree(json).get("text").asString()).hasSize(201);
        assertThat(mapper.readTree(json).get("many").size()).isEqualTo(20);
    }

    @Test
    void emptyDetailsStoreNothingAndOversizedDetailsAreReplaced() {
        assertThat(AuditDetails.none().toJson(mapper)).isNull();
        AuditDetails big = AuditDetails.none();
        for (int i = 0; i < 40; i++) {
            big.and("field" + i, "y".repeat(150));
        }
        assertThat(big.toJson(mapper)).isEqualTo("{\"note\":\"details truncated\"}");
    }
}
