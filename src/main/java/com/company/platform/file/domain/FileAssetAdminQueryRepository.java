package com.company.platform.file.domain;

import com.company.platform.file.application.admin.FileAdminView;
import com.company.platform.file.application.admin.FileSearchCriteria;
import com.company.platform.file.application.admin.FileSource;
import com.company.platform.file.application.admin.FileStats;
import com.company.platform.shared.pagination.KeysetCursor;
import com.company.platform.shared.pagination.UtcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Query đọc file_assets cho admin API — SQL động + keyset {@code (created_at, id) DESC} khớp index
 * {@code idx_file_assets_created_id} (changeset 014). Luôn giới hạn theo khoảng thời gian.
 */
@Repository
public class FileAssetAdminQueryRepository {

  static final String COLUMNS =
      "id, original_filename, mime_type, size_bytes, category_code, storage_status, scan_status,"
          + " owner_type, owner_id, uploaded_by, job_id, checksum_sha256, created_at, updated_at,"
          + " expires_at, deleted_at";

  private final NamedParameterJdbcTemplate jdbc;

  public FileAssetAdminQueryRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Trả tối đa {@code fetch} dòng (caller lấy dư 1 để biết còn trang sau). */
  public List<FileAdminView> search(FileSearchCriteria c, KeysetCursor cursor, int fetch) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(COLUMNS)
            .append(" FROM file_assets WHERE created_at >= :from AND created_at < :to");
    MapSqlParameterSource p =
        new MapSqlParameterSource()
            .addValue("from", UtcTimes.toDb(c.range().from()))
            .addValue("to", UtcTimes.toDb(c.range().to()))
            .addValue("fetch", fetch);
    appendFilters(sql, p, c);
    if (cursor != null) {
      sql.append(" AND (created_at, id) < (:cursorAt, :cursorId)");
      p.addValue("cursorAt", UtcTimes.toDb(cursor.createdAt())).addValue("cursorId", cursor.id());
    }
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT :fetch");
    return jdbc.query(sql.toString(), p, ROW_MAPPER);
  }

  public Optional<FileAdminView> findById(UUID id) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM file_assets WHERE id = :id",
            new MapSqlParameterSource("id", id),
            ROW_MAPPER)
        .stream()
        .findFirst();
  }

  /** {@code GROUP BY <column>} trong phạm vi bộ lọc, sắp theo số file giảm dần rồi theo key. */
  public List<FileStats.Bucket> groupBy(FileSearchCriteria c, String column) {
    if (!List.of("storage_status", "category_code", "mime_type").contains(column)) {
      throw new IllegalArgumentException("Không hỗ trợ group by " + column);
    }
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(column)
            .append(" AS k, count(*) AS files, coalesce(sum(size_bytes), 0) AS bytes")
            .append(" FROM file_assets WHERE created_at >= :from AND created_at < :to");
    MapSqlParameterSource p = rangeParams(c);
    appendFilters(sql, p, c);
    sql.append(" GROUP BY ").append(column).append(" ORDER BY files DESC, k ASC");
    return jdbc.query(
        sql.toString(),
        p,
        (rs, i) ->
            new FileStats.Bucket(rs.getString("k"), rs.getLong("files"), rs.getLong("bytes")));
  }

  /**
   * Gom theo ngày ở {@code zone}: created_at là giờ UTC không kèm múi giờ ({@code
   * hibernate.jdbc.time_zone: UTC}) nên phải gắn UTC trước rồi mới đổi sang {@code zone}.
   */
  public List<FileStats.DailyBucket> daily(FileSearchCriteria c, ZoneId zone) {
    String day = "((created_at AT TIME ZONE 'UTC') AT TIME ZONE :zone)::date";
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(day)
            .append(" AS d, count(*) AS files, coalesce(sum(size_bytes), 0) AS bytes")
            .append(" FROM file_assets WHERE created_at >= :from AND created_at < :to");
    MapSqlParameterSource p = rangeParams(c).addValue("zone", zone.getId());
    appendFilters(sql, p, c);
    sql.append(" GROUP BY d ORDER BY d ASC");
    return jdbc.query(
        sql.toString(),
        p,
        (rs, i) ->
            new FileStats.DailyBucket(
                rs.getObject("d", LocalDate.class), rs.getLong("files"), rs.getLong("bytes")));
  }

  private static MapSqlParameterSource rangeParams(FileSearchCriteria c) {
    return new MapSqlParameterSource()
        .addValue("from", UtcTimes.toDb(c.range().from()))
        .addValue("to", UtcTimes.toDb(c.range().to()));
  }

  /** Bộ lọc dùng chung với thống kê (trừ cursor/ORDER BY). */
  static void appendFilters(StringBuilder sql, MapSqlParameterSource p, FileSearchCriteria c) {
    if (!c.includeDeleted()) {
      sql.append(" AND deleted_at IS NULL");
    }
    if (c.categoryCode() != null) {
      sql.append(" AND category_code = :categoryCode");
      p.addValue("categoryCode", c.categoryCode());
    }
    if (!c.storageStatuses().isEmpty()) {
      sql.append(" AND storage_status IN (:storageStatuses)");
      p.addValue("storageStatuses", c.storageStatuses().stream().map(Enum::name).toList());
    }
    if (c.scanStatus() != null) {
      sql.append(" AND scan_status = :scanStatus");
      p.addValue("scanStatus", c.scanStatus().name());
    }
    if (c.mimeType() != null) {
      if (c.mimeType().endsWith("/")) {
        sql.append(" AND mime_type LIKE :mimePrefix ESCAPE '\\'");
        p.addValue("mimePrefix", escapeLike(c.mimeType()) + "%");
      } else {
        sql.append(" AND mime_type = :mimeType");
        p.addValue("mimeType", c.mimeType());
      }
    }
    if (c.uploadedBy() != null) {
      sql.append(" AND uploaded_by = :uploadedBy");
      p.addValue("uploadedBy", c.uploadedBy());
    }
    if (c.ownerType() != null) {
      sql.append(" AND owner_type = :ownerType");
      p.addValue("ownerType", c.ownerType());
    }
    if (c.ownerId() != null) {
      sql.append(" AND owner_id = :ownerId");
      p.addValue("ownerId", c.ownerId());
    }
    if (c.filename() != null) {
      sql.append(" AND original_filename ILIKE :filename ESCAPE '\\'");
      p.addValue("filename", "%" + escapeLike(c.filename()) + "%");
    }
    if (c.source() == FileSource.USER_UPLOAD) {
      sql.append(" AND job_id IS NULL");
    } else if (c.source() == FileSource.JOB_GENERATED) {
      sql.append(" AND job_id IS NOT NULL");
    }
  }

  static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static final RowMapper<FileAdminView> ROW_MAPPER =
      (ResultSet rs, int rowNum) ->
          new FileAdminView(
              rs.getObject("id", UUID.class),
              rs.getString("original_filename"),
              rs.getString("mime_type"),
              rs.getLong("size_bytes"),
              rs.getString("category_code"),
              StorageStatus.valueOf(rs.getString("storage_status")),
              ScanStatus.valueOf(rs.getString("scan_status")),
              rs.getString("owner_type"),
              rs.getObject("owner_id", UUID.class),
              rs.getObject("uploaded_by", UUID.class),
              rs.getObject("job_id", UUID.class),
              rs.getString("checksum_sha256"),
              time(rs, "created_at"),
              time(rs, "updated_at"),
              time(rs, "expires_at"),
              time(rs, "deleted_at"));

  private static java.time.Instant time(ResultSet rs, String column) throws SQLException {
    return UtcTimes.fromDb(rs.getObject(column, LocalDateTime.class));
  }
}
