package com.example.server.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordUtilsTest {

    private final PasswordUtils passwordUtils = new PasswordUtils();

    @Test
    @DisplayName("A hashed password verifies against itself")
    void hashThenMatchesSucceeds() {
        String stored = passwordUtils.hash("correct-horse-battery-staple");

        assertThat(passwordUtils.matches("correct-horse-battery-staple", stored)).isTrue();
    }

    @Test
    @DisplayName("A wrong password does not verify")
    void wrongPasswordFails() {
        String stored = passwordUtils.hash("correct-horse-battery-staple");

        assertThat(passwordUtils.matches("wrong-password", stored)).isFalse();
    }

    @Test
    @DisplayName("The same password hashed twice yields different digests (unique salt per hash)")
    void saltMakesEachHashUnique() {
        String first = passwordUtils.hash("same-password");
        String second = passwordUtils.hash("same-password");

        // Distinct salts => distinct stored values, so identical passwords are not
        // detectable by comparing digests, and one rainbow table cannot crack many rows.
        assertThat(first).isNotEqualTo(second);
        assertThat(passwordUtils.matches("same-password", first)).isTrue();
        assertThat(passwordUtils.matches("same-password", second)).isTrue();
    }

    @Test
    @DisplayName("Stored format is pbkdf2$<iterations>$<salt>$<hash>")
    void storedFormatIsSelfDescribing() {
        String stored = passwordUtils.hash("whatever");

        assertThat(stored).startsWith("pbkdf2$");
        // Iterations are embedded so the cost factor can be raised later without
        // invalidating existing rows.
        assertThat(stored.split("\\$")).hasSize(4);
    }

    @Test
    @DisplayName("Null inputs are rejected without throwing")
    void nullsAreRejected() {
        assertThat(passwordUtils.matches(null, "anything")).isFalse();
        assertThat(passwordUtils.matches("anything", null)).isFalse();
        assertThat(passwordUtils.matches(null, null)).isFalse();
    }

    @Test
    @DisplayName("Legacy plaintext rows still verify (backward compatibility)")
    void legacyPlaintextRowsStillVerify() {
        // Rows written before hashing was introduced are stored raw; matches()
        // falls back to a constant-time compare instead of failing the login.
        assertThat(passwordUtils.matches("plain123", "plain123")).isTrue();
        assertThat(passwordUtils.matches("plain123", "different")).isFalse();
    }
}
