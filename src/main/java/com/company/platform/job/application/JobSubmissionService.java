package com.company.platform.job.application;

import com.company.platform.job.application.JobSubmissionProperties.RateLimitConfig;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobLimitExceededException;
import com.company.platform.job.domain.JobRateLimitExceededException;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import com.company.platform.job.domain.JobType;
import com.company.platform.job.messaging.JobEventPublisher;
import com.company.platform.job.messaging.JobMessage;
import com.company.platform.shared.ratelimit.RateLimiterService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Cố tình KHÔNG @Transactional ở cấp method: nếu bọc chung 1 transaction, khi
// jobRepository.save(job) ném DataIntegrityViolationException (đụng UNIQUE idempotency_key),
// Postgres sẽ abort toàn bộ transaction đó — mọi câu lệnh sau (kể cả findByIdempotencyKey để
// lấy lại job đã tồn tại) sẽ thất bại theo ("current transaction is aborted"). Để mỗi repository
// call tự có transaction riêng (mặc định của Spring Data JPA) thì lỗi ở save() không ảnh hưởng
// tới câu query riêng biệt ngay sau đó. Đánh đổi: count-check + insert không hoàn toàn atomic —
// chấp nhận được vì đây là "soft limit" (mục 6.4), không phải bất biến trạng thái bắt buộc như
// CAS status transition.
@Service
@EnableConfigurationProperties(JobSubmissionProperties.class)
public class JobSubmissionService {

  private static final Logger log = LoggerFactory.getLogger(JobSubmissionService.class);
  private static final List<JobStatus> ACTIVE_STATUSES =
      List.of(JobStatus.PENDING, JobStatus.PROCESSING);

  private final JobRepository jobRepository;
  private final RateLimiterService rateLimiterService;
  private final ObjectProvider<JobEventPublisher> jobEventPublisherProvider;
  private final ObjectMapper objectMapper;
  private final JobSubmissionProperties properties;

  public JobSubmissionService(
      JobRepository jobRepository,
      RateLimiterService rateLimiterService,
      ObjectProvider<JobEventPublisher> jobEventPublisherProvider,
      ObjectMapper objectMapper,
      JobSubmissionProperties properties) {
    this.jobRepository = jobRepository;
    this.rateLimiterService = rateLimiterService;
    this.jobEventPublisherProvider = jobEventPublisherProvider;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  public Job submit(JobType jobType, JsonNode payload, String idempotencyKey, UUID userId) {
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      Optional<Job> existing = jobRepository.findByIdempotencyKey(idempotencyKey);
      if (existing.isPresent()) {
        return existing.get();
      }
    }

    long activeCount = jobRepository.countByCreatedByAndStatusIn(userId, ACTIVE_STATUSES);
    if (activeCount >= properties.maxActivePerUser()) {
      throw new JobLimitExceededException(userId, properties.maxActivePerUser());
    }

    RateLimitConfig rateLimitConfig = properties.rateLimit().get(jobType);
    if (rateLimitConfig == null) {
      throw new IllegalStateException(
          "Chưa cấu hình rate limit cho JobType %s (app.job.rate-limit.%s)"
              .formatted(jobType, jobType));
    }
    String rateLimitKey = "user:" + userId + ":job:create:" + jobType.name();
    boolean allowed =
        rateLimiterService.tryConsume(
            rateLimitKey,
            rateLimitConfig.capacity(),
            rateLimitConfig.refillTokens(),
            rateLimitConfig.refillPeriod());
    if (!allowed) {
      throw new JobRateLimitExceededException(userId, jobType);
    }

    Map<String, Object> payloadMap =
        objectMapper.convertValue(payload, new TypeReference<Map<String, Object>>() {});
    Job job = new Job(UUID.randomUUID(), jobType, payloadMap, null, null, idempotencyKey, userId);

    Job savedJob;
    try {
      savedJob = jobRepository.save(job);
    } catch (DataIntegrityViolationException raceOnIdempotencyKey) {
      // 2 request cùng idempotencyKey submit gần như đồng thời, request kia đã tạo job trước —
      // trả về job đó thay vì lỗi, đúng ngữ nghĩa idempotent create.
      return jobRepository
          .findByIdempotencyKey(idempotencyKey)
          .orElseThrow(() -> raceOnIdempotencyKey);
    }

    publishBestEffort(savedJob, jobType);
    return savedJob;
  }

  private void publishBestEffort(Job job, JobType jobType) {
    jobEventPublisherProvider.ifAvailable(
        publisher -> {
          try {
            publisher.publish(new JobMessage(job.getId(), jobType));
          } catch (Exception e) {
            log.warn("Không publish được JobMessage cho job {}: {}", job.getId(), e.getMessage());
          }
        });
  }
}
