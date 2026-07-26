package com.company.platform.file.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileCategoryRepository extends JpaRepository<FileCategory, String> {

  List<FileCategory> findAllByRetentionDaysIsNotNull();
}
