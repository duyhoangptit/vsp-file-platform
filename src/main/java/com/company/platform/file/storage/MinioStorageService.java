package com.company.platform.file.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketLifecycleArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.UploadObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import io.minio.messages.Filter;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.Status;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class MinioStorageService {

  private final MinioClient minioClient;

  public MinioStorageService(MinioClient minioClient) {
    this.minioClient = minioClient;
  }

  // Lifecycle rule chỉ được set khi bucket vừa tạo mới (mục 10 design doc) — nếu category đổi
  // retention_days sau khi bucket đã tồn tại, rule cũ trên bucket đó không tự cập nhật lại.
  public void ensureBucketExists(String bucketName, Integer retentionDays) {
    try {
      boolean exists =
          minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
      if (!exists) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        applyRetentionLifecycle(bucketName, retentionDays);
      }
    } catch (MinioException e) {
      throw new MinioStorageException("Không thể tạo/kiểm tra bucket: " + bucketName, e);
    }
  }

  // retentionDays null = giữ file vĩnh viễn, không set lifecycle rule.
  private void applyRetentionLifecycle(String bucketName, Integer retentionDays)
      throws MinioException {
    if (retentionDays == null) {
      return;
    }
    LifecycleConfiguration.Rule rule =
        new LifecycleConfiguration.Rule(
            Status.ENABLED,
            null,
            new LifecycleConfiguration.Expiration((ZonedDateTime) null, retentionDays, null, null),
            new Filter(""),
            "retention-policy",
            null,
            null,
            null);
    minioClient.setBucketLifecycle(
        SetBucketLifecycleArgs.builder()
            .bucket(bucketName)
            .config(new LifecycleConfiguration(List.of(rule)))
            .build());
  }

  public String generatePresignedPutUrl(String bucketName, String objectKey, Duration expiry) {
    return presignedUrl(Method.PUT, bucketName, objectKey, expiry);
  }

  public String generatePresignedGetUrl(String bucketName, String objectKey, Duration expiry) {
    return presignedUrl(Method.GET, bucketName, objectKey, expiry);
  }

  /**
   * Upload trực tiếp 1 file local lên MinIO — dùng cho file server tự sinh ra (VD export result),
   * khác với luồng presigned PUT do client thực hiện.
   */
  public void uploadFile(String bucketName, String objectKey, Path localFile, String contentType) {
    try {
      minioClient.uploadObject(
          UploadObjectArgs.builder()
              .bucket(bucketName)
              .object(objectKey)
              .filename(localFile.toString())
              .contentType(contentType)
              .build());
    } catch (MinioException e) {
      throw new MinioStorageException(
          "Không thể upload file %s lên %s/%s".formatted(localFile, bucketName, objectKey), e);
    }
  }

  /**
   * Đọc trực tiếp nội dung object từ server (khác presigned GET dành cho browser/HTTP client) —
   * dùng cho worker nền cần tự parse nội dung file (VD ImportUserProcessor đọc CSV), không redirect
   * qua URL. Caller chịu trách nhiệm close() InputStream trả về.
   */
  public InputStream getObject(String bucketName, String objectKey) {
    try {
      return minioClient.getObject(
          GetObjectArgs.builder().bucket(bucketName).object(objectKey).build());
    } catch (MinioException e) {
      throw new MinioStorageException(
          "Không thể đọc object %s/%s".formatted(bucketName, objectKey), e);
    }
  }

  /** Trả rỗng nếu object không tồn tại (NoSuchKey), ném MinioStorageException cho mọi lỗi khác. */
  public Optional<StatObjectResponse> statObject(String bucketName, String objectKey) {
    try {
      return Optional.of(
          minioClient.statObject(
              StatObjectArgs.builder().bucket(bucketName).object(objectKey).build()));
    } catch (ErrorResponseException e) {
      if ("NoSuchKey".equals(e.errorResponse().code())) {
        return Optional.empty();
      }
      throw new MinioStorageException(
          "Không thể lấy metadata object %s/%s".formatted(bucketName, objectKey), e);
    } catch (MinioException e) {
      throw new MinioStorageException(
          "Không thể lấy metadata object %s/%s".formatted(bucketName, objectKey), e);
    }
  }

  private String presignedUrl(Method method, String bucketName, String objectKey, Duration expiry) {
    try {
      return minioClient.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder()
              .method(method)
              .bucket(bucketName)
              .object(objectKey)
              .expiry(Math.toIntExact(expiry.toSeconds()))
              .build());
    } catch (MinioException e) {
      throw new MinioStorageException(
          "Không thể sinh presigned URL (%s) cho %s/%s".formatted(method, bucketName, objectKey),
          e);
    }
  }
}
