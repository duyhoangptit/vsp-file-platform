package com.company.platform.import_.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AppUserRecordPersisterTest {

  private final AppUserWriteRepository appUserWriteRepository = mock(AppUserWriteRepository.class);
  private final AppUserRecordPersister persister =
      new AppUserRecordPersister(appUserWriteRepository);

  @Test
  void should_saveAllMappedEntitiesOnce_when_persistingABatch() {
    UserImportRecord record1 =
        new UserImportRecord("a@b.com", "Alice", "0900000001", "ACTIVE", "MEMBER", "VN", "Hanoi");
    UserImportRecord record2 =
        new UserImportRecord("c@d.com", "Charlie", null, "ACTIVE", "ADMIN", "US", null);

    persister.persistBatch(List.of(record1, record2));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<AppUserRecord>> captor = ArgumentCaptor.forClass(List.class);
    verify(appUserWriteRepository, times(1)).saveAll(captor.capture());
    List<AppUserRecord> saved = captor.getValue();
    assertThat(saved).hasSize(2);
    assertThat(saved.get(0).getId()).isNotNull();
    assertThat(saved.get(0).getEmail()).isEqualTo("a@b.com");
    assertThat(saved.get(1).getEmail()).isEqualTo("c@d.com");
    assertThat(saved.get(0).getId()).isNotEqualTo(saved.get(1).getId());
  }

  @Test
  void should_callSaveAllOncePerBatch_when_persistingMultipleBatches() {
    UserImportRecord record =
        new UserImportRecord("a@b.com", "Alice", null, "ACTIVE", "MEMBER", null, null);

    persister.persistBatch(List.of(record));
    persister.persistBatch(List.of(record));

    verify(appUserWriteRepository, times(2)).saveAll(anyList());
  }
}
