package com.company.platform.job.processor;

@FunctionalInterface
public interface ProgressReporter {
  void report(long processedUnits);
}
