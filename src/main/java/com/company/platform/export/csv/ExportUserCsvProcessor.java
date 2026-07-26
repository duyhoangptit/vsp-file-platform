package com.company.platform.export.csv;

import com.company.platform.export.DataReader;
import com.company.platform.export.ExportKitFactory;
import com.company.platform.export.ExportProcessingException;
import com.company.platform.export.RecordWriter;
import com.company.platform.export.ResultUploader;
import com.company.platform.export.UploadResultCommand;
import com.company.platform.file.domain.FileAsset;
import com.company.platform.job.application.JobStatusService;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobNotFoundException;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobType;
import com.company.platform.job.processor.AbstractJobProcessor;
import com.company.platform.job.processor.JobContext;
import com.company.platform.job.processor.JobResult;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Business case đầu tiên chạy end-to-end cho export CSV (roadmap Phase 4). Chỉ implement validate()
 * + doExecute() theo Template Method của AbstractJobProcessor — toàn bộ CAS
 * status/heartbeat/error-handling đã có sẵn ở lớp cha.
 */
@Component
public class ExportUserCsvProcessor extends AbstractJobProcessor {

  private static final String CATEGORY_CODE = "EXPORT_RESULT";
  private static final String OWNER_TYPE = "USER";

  private final JobRepository jobRepository;
  private final ExportKitFactory<AppUser> kitFactory;
  private final int pageSize;

  public ExportUserCsvProcessor(
      JobStatusService jobStatusService,
      TaskScheduler heartbeatScheduler,
      Executor jobExecutionExecutor,
      JobRepository jobRepository,
      ExportKitFactory<AppUser> kitFactory,
      @Value("${app.export.user-csv.page-size:500}") int pageSize) {
    super(jobStatusService, heartbeatScheduler, jobExecutionExecutor);
    this.jobRepository = jobRepository;
    this.kitFactory = kitFactory;
    this.pageSize = pageSize;
  }

  @Override
  public JobType supports() {
    return JobType.EXPORT_USER_CSV;
  }

  @Override
  protected void validate(Map<String, Object> payload) {
    // Business case đầu tiên: export toàn bộ app_users, không filter. Cố tình không validate gì.
  }

  @Override
  protected JobResult doExecute(JobContext context) {
    Job job =
        jobRepository
            .findById(context.jobId())
            .orElseThrow(() -> new JobNotFoundException(context.jobId()));

    Path tempFile = createTempFile(context.jobId());
    try {
      WriteResult writeResult = writeCsv(context, tempFile);
      FileAsset fileAsset = uploadResult(job, tempFile, writeResult.checksumSha256());
      return new JobResult(fileAsset.getObjectKey(), writeResult.processedUnits());
    } finally {
      deleteQuietly(tempFile);
    }
  }

  private record WriteResult(long processedUnits, String checksumSha256) {}

  // Ghi CSV và tính SHA-256 trong cùng 1 lượt đọc file (DigestOutputStream bọc thẳng
  // OutputStream đích của RecordWriter) thay vì ghi xong rồi đọc lại cả file 1 lần nữa.
  private WriteResult writeCsv(JobContext context, Path tempFile) {
    DataReader<AppUser> dataReader = kitFactory.createDataReader();
    long processedUnits = 0;
    String cursor = null;
    MessageDigest digest = sha256Digest();
    try (OutputStream fileOut = Files.newOutputStream(tempFile);
        DigestOutputStream digestOut = new DigestOutputStream(fileOut, digest);
        RecordWriter<AppUser> writer = kitFactory.createRecordWriter(digestOut)) {
      while (true) {
        Page<AppUser> page = dataReader.readNextPage(cursor, pageSize);
        List<AppUser> content = page.getContent();
        if (content.isEmpty()) {
          break;
        }
        for (AppUser appUser : content) {
          writer.writeRecord(appUser);
          processedUnits++;
        }
        cursor = content.get(content.size() - 1).getId().toString();
        // Báo tiến độ theo batch (1 lần/trang), không phải từng record — tránh spam UPDATE
        // xuống DB (mục 11 design doc).
        context.updateProgress(processedUnits);
      }
    } catch (IOException e) {
      throw new ExportProcessingException("Không ghi được file CSV tạm " + tempFile, e);
    }
    return new WriteResult(processedUnits, HexFormat.of().formatHex(digest.digest()));
  }

  private MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new ExportProcessingException("SHA-256 không khả dụng trên JVM này", e);
    }
  }

  private FileAsset uploadResult(Job job, Path tempFile, String checksum) {
    ResultUploader resultUploader = kitFactory.createResultUploader();
    String originalFilename = "export-users-" + Instant.now().toEpochMilli() + ".csv";
    return resultUploader.upload(
        new UploadResultCommand(
            tempFile,
            CATEGORY_CODE,
            "text/csv",
            originalFilename,
            checksum,
            OWNER_TYPE,
            job.getCreatedBy(),
            job.getCreatedBy(),
            job.getId()));
  }

  private Path createTempFile(UUID jobId) {
    try {
      return Files.createTempFile("export-user-csv-" + jobId + "-", ".csv");
    } catch (IOException e) {
      throw new ExportProcessingException("Không tạo được file tạm cho job " + jobId, e);
    }
  }

  private void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // best-effort cleanup, không ảnh hưởng kết quả job
    }
  }
}
