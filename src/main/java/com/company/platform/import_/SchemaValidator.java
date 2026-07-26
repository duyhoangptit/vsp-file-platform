package com.company.platform.import_;

import java.util.List;

public interface SchemaValidator<T> {

  void validateHeader(List<String> header);

  void validate(T record, long rowNumber);
}
