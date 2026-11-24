package com.company.platform.shared.audit;

import com.company.platform.shared.pagination.KeysetCursor;
import com.company.platform.shared.pagination.TimeRange;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Query đọc audit_logs cho admin — native SQL + keyset {@code (created_at, id) DESC}, map về entity
 * {@link AuditLog} (Hibernate bind/đọc thời gian theo {@code hibernate.jdbc.time_zone: UTC}). Trả
 * tối đa {@code fetch} dòng (caller lấy dư 1 để biết còn trang sau).
 */
@Repository
public class AuditAdminQueryRepository {

  private static final String FILE_ASSET = FileAuditActions.RESOURCE_FILE_ASSET;

  private final EntityManager entityManager;

  public AuditAdminQueryRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public List<AuditLog> downloads(DownloadQuery q, KeysetCursor cursor, int fetch) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT * FROM audit_logs WHERE resource_type = :resourceType"
                + " AND created_at >= :from AND created_at < :to");
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("resourceType", FILE_ASSET);
    range(p, q.range());
    if (q.channel() == DownloadChannel.INTERNAL) {
      sql.append(" AND action = :internal");
      p.put("internal", FileAuditActions.DOWNLOADED_INTERNAL);
    } else if (q.channel() == DownloadChannel.PRESIGNED) {
      sql.append(" AND (action = :issued OR (action = 'READ' AND result = 'DENIED'))");
      p.put("issued", FileAuditActions.DOWNLOAD_URL_ISSUED);
    } else {
      sql.append(
          " AND (action IN (:issued, :internal) OR (action = 'READ' AND result = 'DENIED'))");
      p.put("issued", FileAuditActions.DOWNLOAD_URL_ISSUED);
      p.put("internal", FileAuditActions.DOWNLOADED_INTERNAL);
    }
    if (q.actorId() != null) {
      sql.append(" AND actor_id = :actorId");
      p.put("actorId", q.actorId());
    }
    if (q.fileAssetId() != null) {
      sql.append(" AND resource_id = :fileAssetId");
      p.put("fileAssetId", q.fileAssetId());
    }
    if (q.result() != null) {
      sql.append(" AND result = :result");
      p.put("result", q.result().name());
    }
    return page(sql, p, cursor, fetch);
  }

  public List<AuditLog> forFile(UUID fileAssetId, KeysetCursor cursor, int fetch) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT * FROM audit_logs WHERE resource_type = :resourceType"
                + " AND resource_id = :fileAssetId");
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("resourceType", FILE_ASSET);
    p.put("fileAssetId", fileAssetId);
    return page(sql, p, cursor, fetch);
  }

  public List<AuditLog> configChanges(
      TimeRange range, UUID actorId, String resourceKey, KeysetCursor cursor, int fetch) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT * FROM audit_logs WHERE action IN (:globalUpdated, :categoryUpdated)"
                + " AND created_at >= :from AND created_at < :to");
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("globalUpdated", FileAuditActions.VIRUS_SCAN_GLOBAL_UPDATED);
    p.put("categoryUpdated", FileAuditActions.FILE_CATEGORY_UPDATED);
    range(p, range);
    if (actorId != null) {
      sql.append(" AND actor_id = :actorId");
      p.put("actorId", actorId);
    }
    if (resourceKey != null) {
      sql.append(" AND metadata ->> 'resourceKey' = :resourceKey");
      p.put("resourceKey", resourceKey);
    }
    return page(sql, p, cursor, fetch);
  }

  private static void range(Map<String, Object> p, TimeRange range) {
    p.put("from", range.from());
    p.put("to", range.to());
  }

  @SuppressWarnings("unchecked")
  private List<AuditLog> page(
      StringBuilder sql, Map<String, Object> p, KeysetCursor cursor, int fetch) {
    if (cursor != null) {
      sql.append(" AND (created_at, id) < (:cursorAt, :cursorId)");
      p.put("cursorAt", cursor.createdAt());
      p.put("cursorId", cursor.id());
    }
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT :fetch");
    p.put("fetch", fetch);
    Query query = entityManager.createNativeQuery(sql.toString(), AuditLog.class);
    p.forEach(query::setParameter);
    return query.getResultList();
  }
}
