package com.company.platform.import_;

import java.util.List;

public interface RecordPersister<T> {

  void persistBatch(List<T> records);
}
