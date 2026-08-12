package com.example.server.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the failure handling around the yt-dlp subprocess. The success paths need
 * the real binary and network access, so they belong in an integration test.
 *
 * The asymmetry between the two methods is deliberate and worth pinning down:
 * resolving an id must fail soft (returning null so the import falls back to no
 * dedup), while a failed download must fail loud (so the user is told the link
 * could not be fetched instead of getting an empty record).
 */
class YtDlpUtilsTest {

    private YtDlpUtils ytDlpUtils;

    @BeforeEach
    void setUp() {
        ytDlpUtils = new YtDlpUtils();
        // A binary that does not exist stands in for "yt-dlp is missing or broken"
        ReflectionTestUtils.setField(ytDlpUtils, "ytDlpPath", "/nonexistent/yt-dlp");
        ReflectionTestUtils.setField(ytDlpUtils, "ffmpegDir", "/nonexistent/bin");
    }

    @Test
    @DisplayName("An unresolvable id returns null rather than throwing")
    void unresolvableIdReturnsNull() {
        // Fails soft: MediaController treats null as "cannot dedup this one" and
        // still imports, instead of rejecting the upload outright.
        assertThat(ytDlpUtils.extractSourceId("https://youtu.be/whatever")).isNull();
    }

    @Test
    @DisplayName("A null or blank URL resolves to null without spawning anything")
    void nullUrlResolvesToNull() {
        assertThat(ytDlpUtils.extractSourceId(null)).isNull();
        assertThat(ytDlpUtils.extractSourceId("")).isNull();
    }

    @Test
    @DisplayName("A failed download throws, so the caller can report it")
    void failedDownloadThrows() {
        // Fails loud: uploadUrl turns this into a 500 with the reason, rather than
        // silently inserting a row that points at nothing.
        assertThatThrownBy(() -> ytDlpUtils.downloadVideo("https://youtu.be/whatever"))
                .isInstanceOf(Exception.class);
    }
}
