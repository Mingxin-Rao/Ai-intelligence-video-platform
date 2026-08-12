package com.example.server.utils;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

@Component
public class MinioUtils {

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucketName}")
    private String bucketName;

    @Value("${minio.endpoint}")
    private String endpoint;

    /**
     * Upload a file under an explicit object name (caller passes an MD5-based name so that
     * identical content maps to the same object — natural, idempotent storage-level dedup).
     */
    public String uploadFile(MultipartFile file, String objectName) throws Exception {
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(inputStream, file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );
        }
        // Return the public access URL
        return endpoint + "/" + bucketName + "/" + objectName;
    }

    /**
     * [New] Delete a file from MinIO
     * @param fileUrl The complete URL of the file
     */
    public void removeFile(String fileUrl) {
        try {
            // Parse the filename
            String objectName = fileUrl.substring(fileUrl.lastIndexOf("/") + 1);

            // Call MinIO to delete
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );

            System.out.println(" MinIO file deleted: " + objectName);
        } catch (Exception e) {
            System.err.println(" MinIO delete failed: " + e.getMessage());
        }
    }

    /**
     * Merge staged chunk objects into one object, server-side.
     *
     * MinIO composes from the parts it already holds, so the merge never pulls the
     * bytes back through this process — a 2 GB file costs no application memory or
     * bandwidth. Requires every part except the last to be at least 5 MiB, which is
     * why the chunk size is fixed at that floor.
     *
     * @param partObjectNames chunk object names, already in byte order
     * @return the access URL of the merged object
     */
    public String composeObject(java.util.List<String> partObjectNames, String objectName) throws Exception {
        java.util.List<io.minio.ComposeSource> sources = new java.util.ArrayList<>();
        for (String part : partObjectNames) {
            sources.add(io.minio.ComposeSource.builder()
                    .bucket(bucketName)
                    .object(part)
                    .build());
        }

        minioClient.composeObject(
                io.minio.ComposeObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .sources(sources)
                        .build()
        );

        return endpoint + "/" + bucketName + "/" + objectName;
    }

    /**
     * Delete several objects, used to clear staged chunks after a merge.
     * Failures are logged, not thrown: the merged object already exists, so leftover
     * scratch objects are a cleanup problem rather than a failed upload.
     */
    public void removeObjects(java.util.List<String> objectNames) {
        for (String name : objectNames) {
            try {
                minioClient.removeObject(
                        RemoveObjectArgs.builder().bucket(bucketName).object(name).build());
            } catch (Exception e) {
                System.err.println(" MinIO chunk cleanup failed for " + name + ": " + e.getMessage());
            }
        }
    }

    /**
     * [New] Upload a local File object to MinIO under an explicit (MD5-based) object name.
     */
    public String uploadLocalFile(java.io.File file, String objectName) throws Exception {
        try (java.io.FileInputStream inputStream = new java.io.FileInputStream(file)) {
            minioClient.putObject(
                    io.minio.PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(inputStream, file.length(), -1)
                            .contentType("video/mp4") // Handle as mp4 by default
                            .build()
            );
        }
        return endpoint + "/" + bucketName + "/" + objectName;
    }
}