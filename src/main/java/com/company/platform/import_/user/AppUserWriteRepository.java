package com.company.platform.import_.user;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AppUserWriteRepository extends JpaRepository<AppUserRecord, UUID> {}
