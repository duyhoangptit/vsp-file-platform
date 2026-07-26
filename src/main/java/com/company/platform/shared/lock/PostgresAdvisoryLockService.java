package com.company.platform.shared.lock;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresAdvisoryLockService implements AdvisoryLockService {

  private final EntityManager entityManager;

  public PostgresAdvisoryLockService(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  // pg_try_advisory_xact_lock tự nhả lock khi transaction hiện tại kết thúc (commit/rollback) —
  // không cần unlock thủ công, phải gọi trong cùng transaction với phần việc cần bảo vệ.
  @Override
  public boolean tryLock(long lockKey) {
    Boolean acquired =
        (Boolean)
            entityManager
                .createNativeQuery("SELECT pg_try_advisory_xact_lock(:key)")
                .setParameter("key", lockKey)
                .getSingleResult();
    return Boolean.TRUE.equals(acquired);
  }
}
