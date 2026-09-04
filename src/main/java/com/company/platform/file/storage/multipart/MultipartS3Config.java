package com.company.platform.file.storage.multipart;

import com.company.platform.file.storage.MinioProperties;
import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3Client/S3Presigner trỏ vào MinIO — CHỈ dùng cho luồng multipart (MinIO SDK 9.0.0 không expose
 * Create/Complete/Abort/ListParts). Dựng từ chính app.minio.* nên không thêm key config.
 *
 * <p>path-style bắt buộc (MinIO Docker không có vhost domain); region cố định (MinIO bỏ qua nhưng
 * SigV4 cần); tắt checksum mặc định của SDK v2 để không vỡ chữ ký presigned URL.
 */
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MultipartS3Config {

  private static final Region SIGNING_REGION = Region.US_EAST_1;

  private static StaticCredentialsProvider credentials(MinioProperties p) {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(p.accessKey(), p.secretKey()));
  }

  @Bean
  public S3Client s3MultipartClient(MinioProperties properties) {
    return S3Client.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        .region(SIGNING_REGION)
        .credentialsProvider(credentials(properties))
        .forcePathStyle(true)
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .build();
  }

  @Bean
  public S3Presigner s3MultipartPresigner(MinioProperties properties) {
    return S3Presigner.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        .region(SIGNING_REGION)
        .credentialsProvider(credentials(properties))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }
}
