package com.company.platform.import_.user;

import com.company.platform.export.ResultUploader;
import com.company.platform.export.UploadResultCommand;
import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetNotFoundException;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.FileNotAvailableForDownloadException;
import com.company.platform.file.domain.StorageStatus;
import com.company.platform.file.storage.MinioStorageService;
import com.company.platform.import_.FileParser;
import com.company.platform.import_.ImportKitFactory;
import com.company.platform.import_.ImportProcessingException;
import com.company.platform.import_.RecordPersister;
import com.company.platform.import_.SchemaValidationException;
import com.company.platform.import_.SchemaValidator;
import com.company.platform.job.application.JobStatusService;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobNotFoundException;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobType;
import com.company.platform.job.processor.AbstractJobProcessor;
import com.company.platform.job.processor.JobContext;
import com.company.platform.job.processor.JobResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Business case import thứ 2 (roadmap Phase 6, sau export CSV) — chỉ implement validate() +
 * doExecute() theo Template Method của AbstractJobProcessor, không tự viết flow
 * heartbeat/status/error-handling riêng.
 *
 * <p>Chiến lược lỗi (đã quyết định, không tự suy diễn): lỗi header (thiếu cột bắt buộc) làm FAIL
 * toàn bộ job ngay — không parse được file thì không có gì để tiếp tục. Lỗi ở TỪNG DÒNG dữ liệu
 * (dòng thiếu field bắt buộc, quá độ dài cột...) thì SKIP dòng đó, tiếp tục các dòng còn lại, và
 * ghi báo cáo (row_number + reason) upload lên MinIO qua ResultUploader có sẵn (dùng chung với
 * export, đây là tiện ích generic "upload result file + đăng ký file_assets", không phải logic
 * riêng của CSV export) — job vẫn kết thúc DONE, result_location trỏ tới báo cáo lỗi (null nếu
 * không có dòng nào lỗi).
 */
@Component
public class ImportUserProcessor extends AbstractJobProcessor {

  private static final String ERROR_REPORT_CATEGORY_CODE = "IMPORT_ERROR_REPORT";
  private static final String OWNER_TYPE = "USER";

  private final FileAssetRepository fileAssetRepository;
  private final MinioStorageService minioStorageService;
  private final ImportKitFactory<UserImportRecord> kitFactory;
  private final JobRepository jobRepository;
  private final ResultUploader resultUploader;
  private final int batchSize;

  public ImportUserProcessor(
      JobStatusService jobStatusService,
      TaskScheduler heartbeatScheduler,
      Executor jobExecutionExecutor,
      FileAssetRepository fileAssetRepository,
      MinioStorageService minioStorageService,
      ImportKitFactory<UserImportRecord> kitFactory,
      JobRepository jobRepository,
      ResultUploader resultUploader,
      @Value("${app.import.user-csv.batch-size:500}") int batchSize) {
    super(jobStatusService, heartbeatScheduler, jobExecutionExecutor);
    this.fileAssetRepository = fileAssetRepository;
    this.minioStorageService = minioStorageService;
    this.kitFactory = kitFactory;
    this.jobRepository = jobRepository;
    this.resultUploader = resultUploader;
    this.batchSize = batchSize;
  }

  @Override
  public JobType supports() {
    return JobType.IMPORT_USER;
  }

  @Override
  protected void validate(Map<String, Object> payload) {
    parseFileAssetId(payload);
  }

  @Override
  protected JobResult doExecute(JobContext context) {
    UUID fileAssetId = parseFileAssetId(context.payload());
    FileAsset fileAsset =
        fileAssetRepository
            .findById(fileAssetId)
            .orElseThrow(() -> new FileAssetNotFoundException(fileAssetId));
    if (fileAsset.getStorageStatus() != StorageStatus.CLEAN) {
      throw new FileNotAvailableForDownloadException(fileAssetId, fileAsset.getStorageStatus());
    }

    long processedUnits = 0;
    List<UserImportRecord> batch = new ArrayList<>(batchSize);
    List<RowError> rowErrors = new ArrayList<>();
    try (InputStream objectStream =
            minioStorageService.getObject(fileAsset.getBucketName(), fileAsset.getObjectKey());
        FileParser<UserImportRecord> parser = kitFactory.createFileParser(objectStream)) {
      SchemaValidator<UserImportRecord> validator = kitFactory.createSchemaValidator();
      // Lỗi header (thiếu cột bắt buộc) không bắt ở đây -- cố ý ném thẳng ra ngoài để job FAILED
      // ngay với error_message rõ ràng (không parse được cấu trúc file thì không có gì để skip).
      validator.validateHeader(parser.header());
      RecordPersister<UserImportRecord> persister = kitFactory.createRecordPersister();

      long rowNumber = 0;
      UserImportRecord record;
      while ((record = parser.readNextRecord()) != null) {
        rowNumber++;
        try {
          validator.validate(record, rowNumber);
        } catch (SchemaValidationException e) {
          rowErrors.add(new RowError(rowNumber, e.getReason()));
          continue;
        }
        batch.add(record);
        if (batch.size() == batchSize) {
          persister.persistBatch(List.copyOf(batch));
          processedUnits += batch.size();
          batch.clear();
          context.updateProgress(processedUnits);
        }
      }
      if (!batch.isEmpty()) {
        persister.persistBatch(List.copyOf(batch));
        processedUnits += batch.size();
        context.updateProgress(processedUnits);
      }
    } catch (IOException e) {
      throw new ImportProcessingException("Không đọc được file CSV import " + fileAssetId, e);
    }

    String reportObjectKey =
        rowErrors.isEmpty() ? null : uploadErrorReport(context.jobId(), rowErrors).getObjectKey();
    return new JobResult(reportObjectKey, processedUnits);
  }

  private UUID parseFileAssetId(Map<String, Object> payload) {
    Object rawFileAssetId = payload.get("fileAssetId");
    if (!(rawFileAssetId instanceof String fileAssetIdText) || fileAssetIdText.isBlank()) {
      throw new IllegalArgumentException("Payload thiếu fileAssetId hợp lệ");
    }
    try {
      return UUID.fromString(fileAssetIdText);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "fileAssetId không phải UUID hợp lệ: " + fileAssetIdText, e);
    }
  }

  private FileAsset uploadErrorReport(UUID jobId, List<RowError> rowErrors) {
    Job job = jobRepository.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
    Path tempFile = createTempReportFile(jobId);
    try {
      String checksum = writeReport(tempFile, rowErrors);
      String originalFilename = "import-user-errors-" + Instant.now().toEpochMilli() + ".csv";
      return resultUploader.upload(
          new UploadResultCommand(
              tempFile,
              ERROR_REPORT_CATEGORY_CODE,
              "text/csv",
              originalFilename,
              checksum,
              OWNER_TYPE,
              job.getCreatedBy(),
              job.getCreatedBy(),
              jobId));
    } finally {
      deleteQuietly(tempFile);
    }
  }

  private String writeReport(Path tempFile, List<RowError> rowErrors) {
    MessageDigest digest = sha256Digest();
    CSVFormat format = CSVFormat.DEFAULT.builder().setHeader("row_number", "reason").get();
    try (OutputStream fileOut = Files.newOutputStream(tempFile);
        DigestOutputStream digestOut = new DigestOutputStream(fileOut, digest);
        CSVPrinter printer =
            new CSVPrinter(new OutputStreamWriter(digestOut, StandardCharsets.UTF_8), format)) {
      for (RowError rowError : rowErrors) {
        printer.printRecord(rowError.rowNumber(), rowError.reason());
      }
    } catch (IOException e) {
      throw new ImportProcessingException("Không ghi được báo cáo lỗi import", e);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new ImportProcessingException("SHA-256 không khả dụng trên JVM này", e);
    }
  }

  private Path createTempReportFile(UUID jobId) {
    try {
      return Files.createTempFile("import-user-errors-" + jobId + "-", ".csv");
    } catch (IOException e) {
      throw new ImportProcessingException("Không tạo được file tạm cho báo cáo lỗi " + jobId, e);
    }
  }

  private void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // best-effort cleanup, không ảnh hưởng kết quả job
    }
  }

  private record RowError(long rowNumber, String reason) {}
}
