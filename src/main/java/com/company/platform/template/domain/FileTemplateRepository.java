package com.company.platform.template.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FileTemplateRepository extends JpaRepository<FileTemplate, UUID> {

  @Query(
      "SELECT COALESCE(MAX(t.version), 0) FROM FileTemplate t "
          + "WHERE t.category = :category AND t.templateCode = :templateCode")
  int findMaxVersion(
      @Param("category") String category, @Param("templateCode") String templateCode);

  Optional<FileTemplate> findFirstByCategoryAndTemplateCodeAndVersionAndDeletedAtIsNull(
      String category, String templateCode, int version);

  Optional<FileTemplate> findFirstByCategoryAndTemplateCodeAndDeletedAtIsNullOrderByVersionDesc(
      String category, String templateCode);
}
