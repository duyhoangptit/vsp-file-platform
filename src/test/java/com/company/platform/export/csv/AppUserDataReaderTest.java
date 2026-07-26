package com.company.platform.export.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

class AppUserDataReaderTest {

  private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
  private final AppUserDataReader dataReader = new AppUserDataReader(appUserRepository);

  @Test
  void should_delegateToRepositoryWithPageZero_when_cursorProvided() {
    UUID lastId = UUID.randomUUID();
    AppUser user =
        new AppUser(
            UUID.randomUUID(),
            "a@b.com",
            "Alice",
            null,
            "ACTIVE",
            "MEMBER",
            "VN",
            "Hanoi",
            Instant.now(),
            Instant.now());
    when(appUserRepository.findPage(eq(lastId), eq(PageRequest.of(0, 25))))
        .thenReturn(List.of(user));

    Page<AppUser> page = dataReader.readNextPage(lastId.toString(), 25);

    assertThat(page.getContent()).containsExactly(user);
  }

  @Test
  void should_passNullLastId_when_cursorIsNull() {
    dataReader.readNextPage(null, 25);

    verify(appUserRepository).findPage(isNull(), eq(PageRequest.of(0, 25)));
  }

  @Test
  void should_passNullLastId_when_cursorIsBlank() {
    dataReader.readNextPage("  ", 25);

    verify(appUserRepository).findPage(isNull(), eq(PageRequest.of(0, 25)));
  }
}
