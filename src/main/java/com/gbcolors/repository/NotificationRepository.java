package com.gbcolors.repository;

import com.gbcolors.model.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<NotificationEntity, String> {
    List<NotificationEntity> findByEmailAndIsReadFalseOrderByCreatedAtAsc(String email);
    List<NotificationEntity> findTop30ByEmailOrderByCreatedAtDesc(String email);
}
