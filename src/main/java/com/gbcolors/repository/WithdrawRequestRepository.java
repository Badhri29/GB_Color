package com.gbcolors.repository;

import com.gbcolors.model.WithdrawRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WithdrawRequestRepository extends JpaRepository<WithdrawRequestEntity, String> {
    List<WithdrawRequestEntity> findAllByOrderByRequestedAtDesc();
    List<WithdrawRequestEntity> findTop50ByEmailOrderByRequestedAtDesc(String email);
}
