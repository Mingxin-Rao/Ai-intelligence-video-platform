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