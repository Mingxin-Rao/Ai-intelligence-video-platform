package com.example.server.controller;

import com.example.server.entity.MediaFile;
import com.example.server.interceptor.AuthInterceptor;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.metrics.AppMetrics;
import com.example.server.utils.MinioUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resumable upload behaviour. The properties worth locking down are the ones that
 * decide whether a flaky link costs one chunk or the whole transfer: init must
 * report exactly what is missing, a repeated chunk must be harmless, and merge must
 * refuse to compose an incomplete set rather than produce a corrupt object.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChunkUploadControllerTest {

    private static final long USER_ID = 1L;
    private static final String MD5 = "d41d8cd98f00b204e9800998ecf8427e";
    private static final long CHUNK = ChunkUploadController.CHUNK_SIZE;

    @Mock
    private MediaFileMapper mediaFileMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private MinioUtils minioUtils;
    @Mock
    private AppMetrics metrics;
    @Mock
    private SetOperations<String, String> setOps;

    @InjectMocks
    private ChunkUploadController controller;

    private MockHttpServletRequest authed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthInterceptor.ATTR_UID, USER_ID);
        return request;
    }

    private void redisHas(String... indices) {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(Set.of(indices));
    }

    @Nested
    @DisplayName("init")
    class Init {

        @Test
        @DisplayName("A fresh upload reports every chunk as missing")
        void freshUploadReportsAllMissing() {
            when(mediaFileMapper.selectOne(any())).thenReturn(null);
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.members(anyString())).thenReturn(Set.of());

            // 12 MiB over a 5 MiB chunk size => 3 chunks (the last one partial)
            Map<String, Object> result = controller.init(MD5, 12L * 1024 * 1024, authed());

            assertThat(result.get("status")).isEqualTo("RESUME");
            assertThat(result.get("totalChunks")).isEqualTo(3);
            assertThat(result.get("chunkSize")).isEqualTo(CHUNK);
            assertThat(result.get("missingChunks")).isEqualTo(List.of(0, 1, 2));
        }

        @Test
        @DisplayName("A resumed upload reports only the gaps, not everything")
        void resumedUploadReportsOnlyGaps() {
            when(mediaFileMapper.selectOne(any())).thenReturn(null);
            // Chunks 0 and 2 already landed; 1 and 3 were lost mid-transfer
            redisHas("0", "2");

            Map<String, Object> result = controller.init(MD5, 20L * 1024 * 1024, authed());

            assertThat(result.get("totalChunks")).isEqualTo(4);
            // This is what makes resume cheap: the client re-sends 2 chunks, not 4
            assertThat(result.get("missingChunks")).isEqualTo(List.of(1, 3));
            assertThat(result.get("uploadedChunks")).isEqualTo(List.of(0, 2));
        }

        @Test
        @DisplayName("Content the user already owns needs no transfer at all")
        void alreadyOwnedContentIsInstant() {
            MediaFile owned = new MediaFile();
            owned.setId(55L);
            owned.setVideoMd5(MD5);
            when(mediaFileMapper.selectOne(any())).thenReturn(owned);

            Map<String, Object> result = controller.init(MD5, 20L * 1024 * 1024, authed());

            assertThat(result.get("status")).isEqualTo("INSTANT");
            assertThat(result.get("mediaId")).isEqualTo(55L);
            verify(metrics).recordDedupHit(AppMetrics.SOURCE_FILE);
        }

        @Test
        @DisplayName("A missing hash or a non-positive size is rejected")
        void invalidInputRejected() {
            assertThat(controller.init(null, 100, authed()).get("status")).isEqualTo("ERROR");
            assertThat(controller.init("", 100, authed()).get("status")).isEqualTo("ERROR");
            assertThat(controller.init(MD5, 0, authed()).get("status")).isEqualTo("ERROR");
            assertThat(controller.init(MD5, -1, authed()).get("status")).isEqualTo("ERROR");
        }

        @Test
        @DisplayName("A file smaller than one chunk still needs one chunk")
        void tinyFileStillNeedsOneChunk() {
            when(mediaFileMapper.selectOne(any())).thenReturn(null);
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.members(anyString())).thenReturn(Set.of());

            Map<String, Object> result = controller.init(MD5, 1024, authed());

            assertThat(result.get("totalChunks")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("chunk")
    class Chunk {

        private MockMultipartFile part() {
            return new MockMultipartFile("file", "blob", "application/octet-stream", "chunk-bytes".getBytes());
        }

        @Test
        @DisplayName("A chunk is staged under a per-hash key and recorded in the Set")
        void chunkIsStagedAndRecorded() throws Exception {
            when(redisTemplate.opsForSet()).thenReturn(setOps);

            Map<String, Object> result = controller.chunk(part(), MD5, 2, authed());

            assertThat(result.get("status")).isEqualTo("OK");
            ArgumentCaptor<String> object = ArgumentCaptor.forClass(String.class);
            verify(minioUtils).uploadFile(any(), object.capture());
            // Namespaced by hash so two concurrent uploads cannot collide
            assertThat(object.getValue()).isEqualTo("tmp/" + MD5 + "/2");
            verify(setOps).add("upload:chunks:1:" + MD5, "2");
            // TTL so an abandoned upload does not pin the key forever
            verify(redisTemplate).expire(eq("upload:chunks:1:" + MD5), anyLong(), any());
        }

        @Test
        @DisplayName("Re-sending the same chunk is harmless")
        void resendingSameChunkIsHarmless() throws Exception {
            when(redisTemplate.opsForSet()).thenReturn(setOps);

            controller.chunk(part(), MD5, 2, authed());
            controller.chunk(part(), MD5, 2, authed());

            // A weak network is precisely when clients re-send. The Set absorbs the
            // duplicate, so no dedupe logic is needed and the count stays correct.
            verify(setOps, org.mockito.Mockito.times(2)).add("upload:chunks:1:" + MD5, "2");
        }

        @Test
        @DisplayName("A storage failure is reported, not thrown, so the client can retry")
        void storageFailureIsReported() throws Exception {
            when(minioUtils.uploadFile(any(), anyString())).thenThrow(new RuntimeException("connection reset"));

            Map<String, Object> result = controller.chunk(part(), MD5, 1, authed());

            assertThat(result.get("status")).isEqualTo("ERROR");
            assertThat(result.get("chunkIndex")).isEqualTo(1);
            // Crucially the index is NOT recorded, so init will still report it missing
            verify(setOps, never()).add(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("merge")
    class Merge {

        @Test
        @DisplayName("A complete set is composed server-side and recorded")
        void completeSetIsComposed() throws Exception {
            redisHas("0", "1", "2");
            when(mediaFileMapper.selectOne(any())).thenReturn(null);
            when(minioUtils.composeObject(any(), anyString()))
                    .thenReturn("http://minio:9000/media/" + MD5 + ".mp4");

            Map<String, Object> result = controller.merge(MD5, "movie.mp4", 3, authed());

            assertThat(result.get("status")).isEqualTo("COMPLETE");

            // Parts must be composed in byte order or the video is scrambled
            ArgumentCaptor<List<String>> parts = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
            verify(minioUtils).composeObject(parts.capture(), target.capture());
            assertThat(parts.getValue()).containsExactly(
                    "tmp/" + MD5 + "/0", "tmp/" + MD5 + "/1", "tmp/" + MD5 + "/2");
            assertThat(target.getValue()).isEqualTo(MD5 + ".mp4");

            // The fingerprint is stored so a later upload of the same file is instant
            ArgumentCaptor<MediaFile> saved = ArgumentCaptor.forClass(MediaFile.class);
            verify(mediaFileMapper).insert(saved.capture());
            assertThat(saved.getValue().getVideoMd5()).isEqualTo(MD5);
            assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);

            // Scratch state is only dropped once the merged object exists
            verify(minioUtils).removeObjects(any());
            verify(redisTemplate).delete("upload:chunks:1:" + MD5);
        }

        @Test
        @DisplayName("An incomplete set is refused with the gaps, never composed")
        void incompleteSetIsRefused() throws Exception {
            redisHas("0", "2");

            Map<String, Object> result = controller.merge(MD5, "movie.mp4", 4, authed());

            assertThat(result.get("status")).isEqualTo("INCOMPLETE");
            assertThat(result.get("missingChunks")).isEqualTo(List.of(1, 3));
            // Composing a gapped set would yield a silently corrupt video
            verify(minioUtils, never()).composeObject(any(), anyString());
            verify(mediaFileMapper, never()).insert(any(MediaFile.class));
            // ...and the staged chunks must survive so the client can fill the gaps
            verify(minioUtils, never()).removeObjects(any());
            verify(redisTemplate, never()).delete(anyString());
        }

        @Test
        @DisplayName("A compose failure keeps the chunks so only the merge is retried")
        void composeFailureKeepsChunks() throws Exception {
            redisHas("0", "1");
            when(mediaFileMapper.selectOne(any())).thenReturn(null);
            when(minioUtils.composeObject(any(), anyString())).thenThrow(new RuntimeException("compose failed"));

            Map<String, Object> result = controller.merge(MD5, "movie.mp4", 2, authed());

            assertThat(result.get("status")).isEqualTo("ERROR");
            // Re-uploading gigabytes because a merge failed would defeat the point
            verify(minioUtils, never()).removeObjects(any());
            verify(redisTemplate, never()).delete(anyString());
        }

        @Test
        @DisplayName("Merging content that already exists is idempotent")
        void mergingExistingContentIsIdempotent() throws Exception {
            redisHas("0");
            MediaFile owned = new MediaFile();
            owned.setId(77L);
            when(mediaFileMapper.selectOne(any())).thenReturn(owned);

            Map<String, Object> result = controller.merge(MD5, "movie.mp4", 1, authed());

            assertThat(result.get("status")).isEqualTo("INSTANT");
            assertThat(result.get("mediaId")).isEqualTo(77L);
            verify(minioUtils, never()).composeObject(any(), anyString());
            verify(mediaFileMapper, never()).insert(any(MediaFile.class));
        }
    }
}
