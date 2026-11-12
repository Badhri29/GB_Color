package com.gbcolors.repository;

import com.gbcolors.model.AdminMessageLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminMessageLogRepository extends JpaRepository<AdminMessageLogEntity, String> {
    List<AdminMessageLogEntity> findTop50ByOrderByCreatedAtDesc();
}
