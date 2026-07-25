package com.company.platform.job.scheduler;

import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.processor.JobContext;
import com.company.platform.job.processor.JobProcessor;
import com.company.platform.job.processor.JobProcessorFactory;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JobScheduler {

  private final JobRepository jobRepository;
  private final JobProcessorFactory jobProcessorFactory;

  public JobScheduler(JobRepository jobRepository, JobProcessorFactory jobProcessorFactory) {
    this.jobRepository = jobRepository;
    this.jobProcessorFactory = jobProcessorFactory;
  }

  @Transactional
  public Optional<Job> pollNext() {
    return jobRepository.findNextPendingForUpdateSkipLocked();
  }

  // Cố tình KHÔNG @Transactional: pollNext() có transaction riêng của nó, đã commit (nhả
  // lock) trước khi processor.process() chạy — process() có thể kéo dài (export/import), giữ
  // transaction xuyên suốt ở đây sẽ giữ row lock + connection quá lâu, vi phạm nguyên tắc tách
  // pool DB riêng cho export/import (mục 6.4). Việc gọi this.pollNext() là self-invocation
  // trong cùng bean nên proxy @Transactional của Spring không áp dụng qua lời gọi đó — không
  // sao vì pollNext() chỉ có đúng 1 câu lệnh SQL, Postgres tự bọc thành 1 transaction ngầm
  // (autocommit) dù có proxy hay không.
  public boolean pollAndDispatch() {
    Optional<Job> nextJob = pollNext();
    if (nextJob.isEmpty()) {
      return false;
    }
    Job job = nextJob.get();
    JobProcessor processor = jobProcessorFactory.getProcessor(job.getJobType());
    processor.process(new JobContext(job.getId(), job.getJobType(), job.getPayload()));
    return true;
  }
}
