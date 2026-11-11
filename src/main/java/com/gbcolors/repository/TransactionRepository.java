package com.gbcolors.repository;

import com.gbcolors.model.TransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<TransactionEntity, String> {
    List<TransactionEntity> findTop100ByEmailOrderByCreatedAtDesc(String email);
}
