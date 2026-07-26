package com.company.platform.export.csv;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

  // Keyset pagination (mục 6.4 / CLAUDE.md): luôn WHERE id > :lastId ORDER BY id, không OFFSET.
  // Pageable chỉ dùng để diễn đạt LIMIT — page luôn là 0, con trỏ thật là lastId.
  @Query("SELECT a FROM AppUser a WHERE (:lastId IS NULL OR a.id > :lastId) ORDER BY a.id ASC")
  List<AppUser> findPage(@Param("lastId") UUID lastId, Pageable pageable);
}
