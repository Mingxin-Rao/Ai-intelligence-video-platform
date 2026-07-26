package com.example.server.utils;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

@Component
public class MinioUtils {

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucketName}")
    private String bucketName;

    @Value("${minio.endpoint}")
    private String endpoint;

    /**
     * Upload a file and return its access URL
     */
    public String uploadFile(MultipartFile file) throws Exception {
        // 1. Generate a new filename (UUID prevents name collisions)
        String originalFilename = file.getOriginalFilename();
        String suffix = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String newFilename = UUID.randomUUID().toString() + suffix;

        // 2. Upload to MinIO
        InputStream inputStream = file.getInputStream();
        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(bucketName)
                        .object(newFilename)
                        .stream(inputStream, file.getSize(), -1)
                        .contentType(file.getContentType())
                        .build()
        );

        // 3. Concatenate and return the public access URL
        return endpoint + "/" + bucketName + "/" + newFilename;
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
     * [New] Upload a local File object to MinIO
     */
    public String uploadLocalFile(java.io.File file) throws Exception {
        java.io.FileInputStream inputStream = new java.io.FileInputStream(file);

        minioClient.putObject(
                io.minio.PutObjectArgs.builder()
                        .bucket(bucketName)
                        .object(file.getName()) // The filename already contains a UUID
                        .stream(inputStream, file.length(), -1)
                        .contentType("video/mp4") // Handle as mp4 by default
                        .build()
        );
        inputStream.close();

        return endpoint + "/" + bucketName + "/" + file.getName();
    }
}