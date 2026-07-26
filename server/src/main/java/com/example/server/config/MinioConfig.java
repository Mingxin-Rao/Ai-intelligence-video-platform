package com.example.server.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.accessKey}")
    private String accessKey;

    @Value("${minio.secretKey}")
    private String secretKey;

    @Value("${minio.bucketName}")
    private String bucketName;

    @Bean
    public MinioClient minioClient() {
        try {
            MinioClient client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();

            // Check whether the bucket exists; create it if it does not
            boolean found = client.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                System.out.println("⚠️ MinIO bucket [" + bucketName + "] does not exist, creating it...");
                client.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }

            // Force the permission to Public (read-only permission)
            String policyJson = "{\n" +
                    "  \"Version\": \"2012-10-17\",\n" +
                    "  \"Statement\": [\n" +
                    "    {\n" +
                    "      \"Effect\": \"Allow\",\n" +
                    "      \"Principal\": {\n" +
                    "        \"AWS\": [\n" +
                    "          \"*\"\n" +
                    "        ]\n" +
                    "      },\n" +
                    "      \"Action\": [\n" +
                    "        \"s3:GetObject\"\n" +
                    "      ],\n" +
                    "      \"Resource\": [\n" +
                    "        \"arn:aws:s3:::" + bucketName + "/*\"\n" +
                    "      ]\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}";

            client.setBucketPolicy(
                    SetBucketPolicyArgs.builder()
                            .bucket(bucketName)
                            .config(policyJson)
                            .build()
            );

            System.out.println(" MinIO configured successfully, bucket permission has been forced to Public!");
            return client;

        } catch (Exception e) {
            throw new RuntimeException("MinIO initialization failed", e);
        }
    }
}