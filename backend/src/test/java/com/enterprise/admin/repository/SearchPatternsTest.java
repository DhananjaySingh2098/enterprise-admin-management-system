package com.enterprise.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SearchPatternsTest {

    @Test
    void wrapsLowercasedTrimmedTerm() {
        assertThat(SearchPatterns.contains("  Ada ")).isEqualTo("%ada%");
    }

    @Test
    void escapesLikeWildcardsSoInputIsLiteral() {
        assertThat(SearchPatterns.contains("50%_off\\")).isEqualTo("%50\\%\\_off\\\\%");
    }

    @Test
    void blankMeansNoFilter() {
        assertThat(SearchPatterns.contains(null)).isNull();
        assertThat(SearchPatterns.contains("   ")).isNull();
    }
}
