package com.company.platform.export.csv;

import com.company.platform.export.DataReader;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
class AppUserDataReader implements DataReader<AppUser> {

  private final AppUserRepository appUserRepository;

  AppUserDataReader(AppUserRepository appUserRepository) {
    this.appUserRepository = appUserRepository;
  }

  // Adapter cho cursor dạng String — vẫn dùng keyset bên dưới (findPage với WHERE id >
  // :lastId), không phát sinh COUNT/OFFSET; PageImpl chỉ bọc lại kết quả keyset đã có.
  @Override
  public Page<AppUser> readNextPage(String cursor, int pageSize) {
    UUID afterId = (cursor == null || cursor.isBlank()) ? null : UUID.fromString(cursor);
    PageRequest pageRequest = PageRequest.of(0, pageSize);
    List<AppUser> content = appUserRepository.findPage(afterId, pageRequest);
    return new PageImpl<>(content, pageRequest, content.size());
  }
}
