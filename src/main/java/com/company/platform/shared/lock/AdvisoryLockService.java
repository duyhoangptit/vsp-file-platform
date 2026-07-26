package com.company.platform.shared.lock;

public interface AdvisoryLockService {

  boolean tryLock(long lockKey);
}
