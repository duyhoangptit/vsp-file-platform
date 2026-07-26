package com.company.platform.import_.user;

import com.company.platform.import_.RecordPersister;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class AppUserRecordPersister implements RecordPersister<UserImportRecord> {

  private final AppUserWriteRepository appUserWriteRepository;

  AppUserRecordPersister(AppUserWriteRepository appUserWriteRepository) {
    this.appUserWriteRepository = appUserWriteRepository;
  }

  // 1 transaction / batch (không phải toàn file, không phải từng dòng) — mục 6.4 design doc /
  // checklist CLAUDE.md.
  @Override
  @Transactional
  public void persistBatch(List<UserImportRecord> records) {
    List<AppUserRecord> entities = records.stream().map(this::toEntity).toList();
    appUserWriteRepository.saveAll(entities);
  }

  private AppUserRecord toEntity(UserImportRecord record) {
    return new AppUserRecord(
        UUID.randomUUID(),
        record.email(),
        record.fullName(),
        record.phoneNumber(),
        record.status(),
        record.role(),
        record.country(),
        record.city());
  }
}
