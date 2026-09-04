package com.company.platform.file.storage.multipart;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

/**
 * Adapter mỏng qua AWS SDK v2 cho luồng multipart. KHÔNG chứa business rule (auth, size limit,
 * state machine) — chỉ gọi storage. Chỉ class này (và {@link MultipartS3Config}) được import
 * awssdk.
 */
@Service
public class MultipartStorageService {

  public record UploadedPart(int partNumber, String etag, long sizeBytes) {}

  public record CompletedPartRef(int partNumber, String etag) {}

  private final S3Client s3;
  private final S3Presigner presigner;

  public MultipartStorageService(S3Client s3MultipartClient, S3Presigner s3MultipartPresigner) {
    this.s3 = s3MultipartClient;
    this.presigner = s3MultipartPresigner;
  }

  public String createMultipartUpload(String bucket, String objectKey, String contentType) {
    try {
      return s3.createMultipartUpload(
              CreateMultipartUploadRequest.builder()
                  .bucket(bucket)
                  .key(objectKey)
                  .contentType(contentType)
                  .build())
          .uploadId();
    } catch (S3Exception e) {
      throw new MultipartStorageException(
          "Không mở được multipart upload cho %s/%s".formatted(bucket, objectKey), e);
    }
  }

  public String presignUploadPart(
      String bucket, String objectKey, String uploadId, int partNumber, Duration expiry) {
    UploadPartRequest part =
        UploadPartRequest.builder()
            .bucket(bucket)
            .key(objectKey)
            .uploadId(uploadId)
            .partNumber(partNumber)
            .build();
    return presigner
        .presignUploadPart(
            UploadPartPresignRequest.builder()
                .signatureDuration(expiry)
                .uploadPartRequest(part)
                .build())
        .url()
        .toString();
  }

  public List<UploadedPart> listParts(String bucket, String objectKey, String uploadId) {
    try {
      return s3
          .listPartsPaginator(
              ListPartsRequest.builder().bucket(bucket).key(objectKey).uploadId(uploadId).build())
          .parts()
          .stream()
          .map(p -> new UploadedPart(p.partNumber(), p.eTag(), p.size()))
          .toList();
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return List.of();
      }
      throw new MultipartStorageException(
          "Không liệt kê được part của %s/%s".formatted(bucket, objectKey), e);
    }
  }

  public void completeMultipartUpload(
      String bucket, String objectKey, String uploadId, List<CompletedPartRef> parts) {
    List<CompletedPart> completed =
        parts.stream()
            .sorted(Comparator.comparingInt(CompletedPartRef::partNumber))
            .map(p -> CompletedPart.builder().partNumber(p.partNumber()).eTag(p.etag()).build())
            .toList();
    try {
      s3.completeMultipartUpload(
          CompleteMultipartUploadRequest.builder()
              .bucket(bucket)
              .key(objectKey)
              .uploadId(uploadId)
              .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
              .build());
    } catch (S3Exception e) {
      throw new MultipartStorageException(
          "Không complete được multipart upload %s/%s".formatted(bucket, objectKey), e);
    }
  }

  public void abortMultipartUpload(String bucket, String objectKey, String uploadId) {
    try {
      s3.abortMultipartUpload(
          AbortMultipartUploadRequest.builder()
              .bucket(bucket)
              .key(objectKey)
              .uploadId(uploadId)
              .build());
    } catch (S3Exception e) {
      throw new MultipartStorageException(
          "Không abort được multipart upload %s/%s".formatted(bucket, objectKey), e);
    }
  }
}
