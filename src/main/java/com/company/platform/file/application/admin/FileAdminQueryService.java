package com.company.platform.file.application.admin;

import com.company.platform.file.domain.FileAssetAdminQueryRepository;
import com.company.platform.file.domain.FileAssetNotFoundException;
import com.company.platform.shared.pagination.KeysetCursor;
import com.company.platform.shared.pagination.PageSlice;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Danh sách (= lịch sử upload) và chi tiết file cho admin. */
@Service
@Transactional(readOnly = true)
public class FileAdminQueryService {

  private final FileAssetAdminQueryRepository repository;

  public FileAdminQueryService(FileAssetAdminQueryRepository repository) {
    this.repository = repository;
  }

  public PageSlice<FileAdminView> search(
      FileSearchCriteria criteria, KeysetCursor cursor, int limit) {
    List<FileAdminView> rows = repository.search(criteria, cursor, limit + 1);
    if (rows.size() <= limit) {
      return new PageSlice<>(rows, null);
    }
    List<FileAdminView> page = rows.subList(0, limit);
    FileAdminView last = page.get(limit - 1);
    return new PageSlice<>(
        List.copyOf(page), new KeysetCursor(last.createdAt(), last.id()).encode());
  }

  /** Thấy cả file đã soft-delete. */
  public FileAdminView get(UUID id) {
    return repository.findById(id).orElseThrow(() -> new FileAssetNotFoundException(id));
  }
}
