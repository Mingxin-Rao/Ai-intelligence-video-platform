package com.example.server.controller;

import com.example.server.entity.MediaFile;
import com.example.server.interceptor.AuthInterceptor;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.metrics.AppMetrics;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.YtDlpUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.DigestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for two defects found in real use:
 *
 *  1. Uploading the same video twice created two rows, two MinIO objects and two
 *     paid AI analyses (object names were random UUIDs, so identical content was
 *     invisible to the system).
 *  2. Ownership had to be enforced from the token, never a client-supplied id.
 *
 * The controller is exercised as a plain object with mocked collaborators, so
 * these run in milliseconds with no MySQL/Redis/MinIO involved.
 */
@ExtendWith(MockitoExtension.class)
class MediaControllerTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;

    @Mock
    private MediaFileMapper mediaFileMapper;
    @Mock
    private MinioUtils minioUtils;
    @Mock
    private YtDlpUtils ytDlpUtils;
    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private AppMetrics metrics;

    @InjectMocks
    private MediaController mediaController;

    /** A request that has already passed AuthInterceptor, carrying the resolved uid. */
    private MockHttpServletRequest authedRequest(long uid) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthInterceptor.ATTR_UID, uid);
        return request;
    }

    private MediaFile existingRecord(long id, long ownerId, String md5) {
        MediaFile f = new MediaFile();
        f.setId(id);
        f.setUserId(ownerId);
        f.setVideoMd5(md5);
        f.setFilePath("http://minio:9000/media/" + md5 + ".mp4");
        f.setAiSummary("previously generated summary");
        return f;
    }

    @Nested
    @DisplayName("Content-fingerprint dedup")
    class Dedup {

        @Test
        @DisplayName("Re-uploading identical content skips storage and creates no second row")
        void duplicateUploadIsShortCircuited() throws Exception {
            byte[] bytes = "the very same video bytes".getBytes();
            String md5 = DigestUtils.md5DigestAsHex(bytes);
            MockMultipartFile file = new MockMultipartFile("file", "clip.mp4", "video/mp4", bytes);
            // The user already owns a record with this fingerprint
            when(mediaFileMapper.selectOne(any())).thenReturn(existingRecord(99L, USER_ID, md5));

            String result = mediaController.upload(file, authedRequest(USER_ID));

            assertThat(result).contains("duplicate");
            // The whole point: no re-upload, no duplicate row -> no second AI task, no double API spend
            verify(minioUtils, never()).uploadFile(any(), anyString());
            verify(mediaFileMapper, never()).insert(any(MediaFile.class));
        }

        @Test
        @DisplayName("New content is stored under its MD5 and the fingerprint is persisted")
        void freshUploadIsStoredUnderMd5() throws Exception {
            byte[] bytes = "brand new video bytes".getBytes();
            String md5 = DigestUtils.md5DigestAsHex(bytes);
            MockMultipartFile file = new MockMultipartFile("file", "clip.mp4", "video/mp4", bytes);
            when(mediaFileMapper.selectOne(any())).thenReturn(null);
            when(minioUtils.uploadFile(any(), anyString()))
                    .thenReturn("http://minio:9000/media/" + md5 + ".mp4");

            String result = mediaController.upload(file, authedRequest(USER_ID));

            assertThat(result).isEqualTo("Upload successful");

            // Object name is derived from content, not random -> identical content collapses
            ArgumentCaptor<String> objectName = ArgumentCaptor.forClass(String.class);
            verify(minioUtils).uploadFile(any(), objectName.capture());
            assertThat(objectName.getValue()).isEqualTo(md5 + ".mp4");

            // ...and the fingerprint is stored so the next upload can match it
            ArgumentCaptor<MediaFile> saved = ArgumentCaptor.forClass(MediaFile.class);
            verify(mediaFileMapper).insert(saved.capture());
            assertThat(saved.getValue().getVideoMd5()).isEqualTo(md5);
            assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("Different content produces a different fingerprint (no false dedup)")
        void differentContentIsNotDeduped() throws Exception {
            byte[] bytes = "a completely different video".getBytes();
            String md5 = DigestUtils.md5DigestAsHex(bytes);
            MockMultipartFile file = new MockMultipartFile("file", "other.mov", "video/quicktime", bytes);
            when(mediaFileMapper.selectOne(any())).thenReturn(null);
            when(minioUtils.uploadFile(any(), anyString())).thenReturn("http://minio:9000/media/x");

            mediaController.upload(file, authedRequest(USER_ID));

            ArgumentCaptor<String> objectName = ArgumentCaptor.forClass(String.class);
            verify(minioUtils).uploadFile(any(), objectName.capture());
            // Extension is preserved so FFmpeg/players still see a sane container type
            assertThat(objectName.getValue()).isEqualTo(md5 + ".mov");
        }
    }

    @Nested
    @DisplayName("Link import dedup (by source video id, not content hash)")
    class LinkDedup {

        private static final String SOURCE_ID = "youtube:dQw4w9WgXcQ";

        @Test
        @DisplayName("A link for a video the user already has is refused before downloading")
        void duplicateLinkSkipsTheDownloadEntirely() throws Exception {
            when(ytDlpUtils.extractSourceId(anyString())).thenReturn(SOURCE_ID);
            MediaFile owned = existingRecord(77L, USER_ID, null);
            owned.setSourceVideoId(SOURCE_ID);
            when(mediaFileMapper.selectOne(any())).thenReturn(owned);

            var response = mediaController.uploadUrl("https://youtu.be/dQw4w9WgXcQ?t=30",
                    authedRequest(USER_ID));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).contains("duplicate");
            // The payoff of keying on the id: the expensive download never happens
            verify(ytDlpUtils, never()).downloadVideo(anyString());
            verify(minioUtils, never()).uploadLocalFile(any(), anyString());
            verify(mediaFileMapper, never()).insert(any(MediaFile.class));
        }

        @Test
        @DisplayName("A new link is stored under its source id and the id is persisted")
        void freshLinkIsStoredUnderSourceId(@TempDir Path tempDir) throws Exception {
            File downloaded = tempDir.resolve("random-uuid.mp4").toFile();
            Files.write(downloaded.toPath(), "downloaded video bytes".getBytes());

            when(ytDlpUtils.extractSourceId(anyString())).thenReturn(SOURCE_ID);
            when(mediaFileMapper.selectOne(any())).thenReturn(null);
            when(ytDlpUtils.downloadVideo(anyString())).thenReturn(downloaded);
            when(minioUtils.uploadLocalFile(any(), anyString()))
                    .thenReturn("http://minio:9000/media/youtube_dQw4w9WgXcQ.mp4");

            var response = mediaController.uploadUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                    authedRequest(USER_ID));

            assertThat(response.getStatusCode().value()).isEqualTo(200);

            // The colon is sanitized so the object key stays URL-safe
            ArgumentCaptor<String> objectName = ArgumentCaptor.forClass(String.class);
            verify(minioUtils).uploadLocalFile(any(), objectName.capture());
            assertThat(objectName.getValue()).isEqualTo("youtube_dQw4w9WgXcQ.mp4");

            ArgumentCaptor<MediaFile> saved = ArgumentCaptor.forClass(MediaFile.class);
            verify(mediaFileMapper).insert(saved.capture());
            assertThat(saved.getValue().getSourceVideoId()).isEqualTo(SOURCE_ID);
            // Links are identified by their id alone — no content hash is computed
            assertThat(saved.getValue().getVideoMd5()).isNull();
        }

        @Test
        @DisplayName("An unresolvable link still imports, just without dedup")
        void unresolvableLinkStillImports(@TempDir Path tempDir) throws Exception {
            File downloaded = tempDir.resolve("fallback-name.mp4").toFile();
            Files.write(downloaded.toPath(), "bytes".getBytes());

            // yt-dlp could not resolve an id (private video, odd site, network hiccup)
            when(ytDlpUtils.extractSourceId(anyString())).thenReturn(null);
            when(ytDlpUtils.downloadVideo(anyString())).thenReturn(downloaded);
            when(minioUtils.uploadLocalFile(any(), anyString())).thenReturn("http://minio:9000/media/x");

            var response = mediaController.uploadUrl("https://example.com/video", authedRequest(USER_ID));

            // Fail open: the import succeeds rather than being blocked on a lookup
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            ArgumentCaptor<MediaFile> saved = ArgumentCaptor.forClass(MediaFile.class);
            verify(mediaFileMapper).insert(saved.capture());
            assertThat(saved.getValue().getSourceVideoId()).isNull();
        }
    }

    @Nested
    @DisplayName("Ownership enforcement (IDOR)")
    class Ownership {

        @Test
        @DisplayName("Deleting another user's file is refused and touches nothing")
        void cannotDeleteAnotherUsersFile() {
            when(mediaFileMapper.selectById(50L))
                    .thenReturn(existingRecord(50L, OTHER_USER_ID, "deadbeef"));

            String result = mediaController.delete(50L, authedRequest(USER_ID));

            assertThat(result).contains("not allowed");
            // Neither the DB row nor the stored object may be removed
            verify(mediaFileMapper, never()).deleteById(any(Long.class));
            verify(minioUtils, never()).removeFile(anyString());
        }

        @Test
        @DisplayName("Deleting your own file removes both the row and the stored object")
        void canDeleteOwnFile() {
            when(mediaFileMapper.selectById(60L))
                    .thenReturn(existingRecord(60L, USER_ID, "cafebabe"));

            String result = mediaController.delete(60L, authedRequest(USER_ID));

            assertThat(result).isEqualTo("Deleted successfully");
            verify(mediaFileMapper).deleteById(60L);
            verify(minioUtils).removeFile(anyString());
        }

        @Test
        @DisplayName("Deleting a non-existent file reports not-found instead of throwing")
        void missingFileIsReportedNotFound() {
            when(mediaFileMapper.selectById(404L)).thenReturn(null);

            String result = mediaController.delete(404L, authedRequest(USER_ID));

            assertThat(result).contains("not found");
            verify(mediaFileMapper, never()).deleteById(any(Long.class));
        }
    }
}
