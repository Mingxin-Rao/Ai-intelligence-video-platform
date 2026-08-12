package com.example.server.utils;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Storage-layer plumbing with a mocked MinIO client. The properties that matter
 * are that the caller-supplied object name is used verbatim (dedup depends on the
 * object key being derived from content, not generated), that the returned URL is
 * assembled correctly since it is what gets persisted and later handed to FFmpeg,
 * and that a delete failure does not propagate.
 */
@ExtendWith(MockitoExtension.class)
class MinioUtilsTest {

    private static final String ENDPOINT = "http://minio:9000";
    private static final String BUCKET = "media";

    @Mock
    private MinioClient minioClient;

    private MinioUtils minioUtils;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        minioUtils = new MinioUtils();
        ReflectionTestUtils.setField(minioUtils, "minioClient", minioClient);
        ReflectionTestUtils.setField(minioUtils, "bucketName", BUCKET);
        ReflectionTestUtils.setField(minioUtils, "endpoint", ENDPOINT);
    }

    @Test
    @DisplayName("A multipart upload uses the given object name and returns its URL")
    void multipartUploadUsesGivenObjectName() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "original.mp4", "video/mp4", "bytes".getBytes());

        String url = minioUtils.uploadFile(file, "abc123.mp4");

        // The object key must be exactly what the caller computed (the content MD5);
        // generating one here would silently break dedup.
        ArgumentCaptor<PutObjectArgs> args = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(args.capture());
        assertThat(args.getValue().object()).isEqualTo("abc123.mp4");
        assertThat(args.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(args.getValue().contentType()).isEqualTo("video/mp4");

        // This URL is persisted as file_path and later read by FFmpeg
        assertThat(url).isEqualTo(ENDPOINT + "/" + BUCKET + "/abc123.mp4");
    }

    @Test
    @DisplayName("A local-file upload uses the given object name and returns its URL")
    void localFileUploadUsesGivenObjectName() throws Exception {
        File local = tempDir.resolve("downloaded.mp4").toFile();
        Files.write(local.toPath(), "video-bytes".getBytes());

        String url = minioUtils.uploadLocalFile(local, "youtube_abc.mp4");

        ArgumentCaptor<PutObjectArgs> args = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(args.capture());
        assertThat(args.getValue().object()).isEqualTo("youtube_abc.mp4");
        assertThat(url).isEqualTo(ENDPOINT + "/" + BUCKET + "/youtube_abc.mp4");
    }

    @Test
    @DisplayName("Delete extracts the object name from the stored URL")
    void deleteExtractsObjectNameFromUrl() throws Exception {
        minioUtils.removeFile(ENDPOINT + "/" + BUCKET + "/abc123.mp4");

        ArgumentCaptor<RemoveObjectArgs> args = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minioClient).removeObject(args.capture());
        assertThat(args.getValue().object()).isEqualTo("abc123.mp4");
        assertThat(args.getValue().bucket()).isEqualTo(BUCKET);
    }

    @Test
    @DisplayName("A storage delete failure does not propagate to the caller")
    void deleteFailureIsSwallowed() throws Exception {
        doThrow(new RuntimeException("minio unreachable"))
                .when(minioClient).removeObject(org.mockito.ArgumentMatchers.any(RemoveObjectArgs.class));

        // The database row has already been removed by this point; throwing here
        // would surface a failure for an operation the user sees as done.
        minioUtils.removeFile(ENDPOINT + "/" + BUCKET + "/abc123.mp4");
    }
}
