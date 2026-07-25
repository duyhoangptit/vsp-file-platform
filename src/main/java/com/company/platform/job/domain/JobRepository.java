package com.company.platform.job.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 1. flushAutomatically = true (Đẩy dữ liệu cũ đi)Cơ chế: Trước khi câu lệnh SQL UPDATE hoặc DELETE
 * của bạn được thực thi, Spring Data JPA sẽ ép EntityManager đẩy toàn bộ các thay đổi chưa lưu
 * (đang nằm trong bộ nhớ đệm) xuống database. Tại sao cần: Nếu bạn có các thay đổi dữ liệu được
 * thực hiện trước đó bằng code Java (ví dụ: user.setName("New Name")) nhưng chưa kịp lưu, hành động
 * flush này đảm bảo database nhận được dữ liệu mới nhất trước khi câu lệnh @Modifying chạy. Điều
 * này giúp tránh việc câu lệnh sửa đổi đè lên dữ liệu cũ hoặc xung đột dữ liệu.
 *
 * <p>2. clearAutomatically = true (Làm sạch bộ nhớ đệm sau khi sửa)Cơ chế: Sau khi câu lệnh SQL
 * của @Modifying thực thi xong , thuộc tính này sẽ tự động xóa sạch (clear) toàn bộ thực thể
 * (entities) đang nằm trong bộ nhớ đệm của EntityManager (First-level cache).Tại sao cần: Các câu
 * lệnh @Modifying chạy trực tiếp bằng SQL thuần xuống database nên Hibernate/JPA không hề biết thực
 * thể trong Java đã bị thay đổi. Nếu không xóa bộ nhớ đệm, khi bạn gọi hàm findById() ngay sau đó,
 * JPA sẽ lấy lại dữ liệu cũ (lúc chưa sửa) từ bộ nhớ đệm ra, dẫn đến hiện tượng dữ liệu bị sai lệch
 * (stale data) giữa Java và Database. Việc xóa cache ép JPA phải SELECT lại dữ liệu mới nhất từ
 * database cho các lần gọi sau.
 */
public interface JobRepository extends JpaRepository<Job, UUID> {

  // Native SQL vì JPQL không hỗ trợ SKIP LOCKED (cú pháp riêng của Postgres) — mục 6.3
  // design doc: priority không xử lý ở tầng message broker, quyền quyết định thứ tự luôn
  // nằm ở DB.
  // 1. Chống tranh chấp (Race Condition): Khi có nhiều worker cùng truy vấn lấy việc để làm cùng
  // một thời điểm,
  // nếu không có khóa, các worker dễ đọc và xử lý nhầm cùng một job
  // 2. Tránh bị đứng chờ (Blocking): Nếu chỉ dùng FOR UPDATE, worker thứ hai sẽ bị treo (chờ worker
  // thứ nhất hoàn thành transaction),
  // gây nghẽn hệ thống (bottleneck).
  // 3. Tối ưu hiệu năng hàng đợi (Queue Processing): SKIP LOCKED giúp mỗi worker tự động bỏ qua các
  // job đang bị giữ
  // và lấy ngay job tiếp theo chưa bị khóa, giúp các tiến trình chạy mượt mà, độc lập và không tốn
  // công chờ đợi.
  @Query(
      value =
          "SELECT * FROM jobs WHERE status = 'PENDING' "
              + "ORDER BY priority DESC, created_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  Optional<Job> findNextPendingForUpdateSkipLocked();

  long countByCreatedByAndStatusIn(UUID createdBy, Collection<JobStatus> statuses);

  Optional<Job> findByIdempotencyKey(String idempotencyKey);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE Job j SET j.status = :newStatus, j.updatedAt = :now "
          + "WHERE j.id = :id AND j.status = :expectedStatus")
  int casUpdateStatus(
      @Param("id") UUID id,
      @Param("expectedStatus") JobStatus expectedStatus,
      @Param("newStatus") JobStatus newStatus,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE Job j SET j.status = :newStatus, j.resultLocation = :resultLocation, "
          + "j.processedUnits = :processedUnits, j.updatedAt = :now "
          + "WHERE j.id = :id AND j.status = :expectedStatus")
  int casUpdateStatusToDone(
      @Param("id") UUID id,
      @Param("expectedStatus") JobStatus expectedStatus,
      @Param("newStatus") JobStatus newStatus,
      @Param("resultLocation") String resultLocation,
      @Param("processedUnits") long processedUnits,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE Job j SET j.status = :newStatus, j.errorMessage = :errorMessage, j.updatedAt = :now "
          + "WHERE j.id = :id AND j.status = :expectedStatus")
  int casUpdateStatusToFailed(
      @Param("id") UUID id,
      @Param("expectedStatus") JobStatus expectedStatus,
      @Param("newStatus") JobStatus newStatus,
      @Param("errorMessage") String errorMessage,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE Job j SET j.heartbeatAt = :heartbeatAt "
          + "WHERE j.id = :id AND j.status = :expectedStatus")
  int casUpdateHeartbeat(
      @Param("id") UUID id,
      @Param("expectedStatus") JobStatus expectedStatus,
      @Param("heartbeatAt") Instant heartbeatAt);
}
