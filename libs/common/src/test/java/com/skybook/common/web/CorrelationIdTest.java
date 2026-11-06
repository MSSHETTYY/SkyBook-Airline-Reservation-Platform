package com.skybook.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

class CorrelationIdTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "3f2b8c1e-8d1a-4f7e-9a51-0c2d6f3b9e11", "gw:req.42_x-1"})
    void acceptsSafeValues(String value) {
        assertThat(CorrelationId.isValid(value)).isTrue();
        assertThat(CorrelationId.acceptOrGenerate(value)).isEqualTo(value);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"evil\r\nINFO forged log line", "has space", "<script>", "quote\"d", "ü-unicode"})
    void rejectsUnsafeValues(String value) {
        assertThat(CorrelationId.isValid(value)).isFalse();
        assertThat(CorrelationId.acceptOrGenerate(value)).isNotEqualTo(value).satisfies(CorrelationIdTest::isUuid);
    }

    @Test
    void rejectsValuesLongerThanMaxLength() {
        String tooLong = "a".repeat(CorrelationId.MAX_LENGTH + 1);
        assertThat(CorrelationId.isValid("a".repeat(CorrelationId.MAX_LENGTH))).isTrue();
        assertThat(CorrelationId.isValid(tooLong)).isFalse();
    }

    @Test
    void generatesDistinctIds() {
        assertThat(CorrelationId.generate()).isNotEqualTo(CorrelationId.generate());
    }

    @Test
    void currentReflectsMdc() {
        assertThat(CorrelationId.current()).isEmpty();
        MDC.put(CorrelationId.MDC_KEY, "abc");
        assertThat(CorrelationId.current()).contains("abc");
    }

    private static void isUuid(String value) {
        assertThat(UUID.fromString(value)).isNotNull();
    }
}
