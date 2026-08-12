package com.example.server.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security tests for token issuing/verification.
 *
 * These matter because every ownership check in the app derives the userId from
 * the token — if a token can be forged or tampered with, every authorization
 * check downstream is void.
 */
class JwtUtilsTest {

    private static final String SECRET = "test-secret-please-do-not-use-in-prod";
    private static final long SEVEN_DAYS_MS = 604_800_000L;

    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtils, "expireMs", SEVEN_DAYS_MS);
    }

    @Test
    @DisplayName("A freshly issued token round-trips back to the same userId")
    void generatedTokenResolvesToSameUserId() {
        String token = jwtUtils.generate(42L);

        assertThat(jwtUtils.parseUserId(token)).isEqualTo(42L);
    }

    @Test
    @DisplayName("Tampering with the payload (privilege escalation attempt) is rejected")
    void tamperedPayloadIsRejected() {
        // Attacker takes their own valid token for uid=42 and rewrites it to uid=1
        String token = jwtUtils.generate(42L);
        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"uid\":1,\"exp\":9999999999999}".getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        // Signature no longer matches the body, so it must not resolve at all
        assertThat(jwtUtils.parseUserId(forged)).isNull();
    }

    @Test
    @DisplayName("Tampering with the signature is rejected")
    void tamperedSignatureIsRejected() {
        String token = jwtUtils.generate(7L);
        String[] parts = token.split("\\.");
        String forged = parts[0] + "." + parts[1] + ".aaaaaaaaaaaaaaaaaaaaaaaaaaaa";

        assertThat(jwtUtils.parseUserId(forged)).isNull();
    }

    @Test
    @DisplayName("A token signed with a different secret is rejected")
    void tokenFromForeignSecretIsRejected() {
        JwtUtils attacker = new JwtUtils();
        ReflectionTestUtils.setField(attacker, "secret", "some-other-secret");
        ReflectionTestUtils.setField(attacker, "expireMs", SEVEN_DAYS_MS);
        String foreignToken = attacker.generate(1L);

        assertThat(jwtUtils.parseUserId(foreignToken)).isNull();
    }

    @Test
    @DisplayName("An expired token is rejected")
    void expiredTokenIsRejected() {
        // Negative validity => exp is already in the past the moment it is issued
        ReflectionTestUtils.setField(jwtUtils, "expireMs", -1000L);
        String expired = jwtUtils.generate(42L);

        assertThat(jwtUtils.parseUserId(expired)).isNull();
    }

    @Test
    @DisplayName("Malformed, blank and null tokens are rejected without throwing")
    void malformedTokensAreRejected() {
        assertThat(jwtUtils.parseUserId(null)).isNull();
        assertThat(jwtUtils.parseUserId("")).isNull();
        assertThat(jwtUtils.parseUserId("   ")).isNull();
        assertThat(jwtUtils.parseUserId("not-a-jwt")).isNull();
        assertThat(jwtUtils.parseUserId("only.two")).isNull();
        assertThat(jwtUtils.parseUserId("!!!.!!!.!!!")).isNull();
    }
}
