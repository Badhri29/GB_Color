package com.gbcolors.repository;

import com.gbcolors.model.BetEntity;
import com.gbcolors.model.GameEnums;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BetRepository extends JpaRepository<BetEntity, String> {
    List<BetEntity> findTop60ByEmailAndStatusNotOrderByCreatedAtDesc(String email, GameEnums.BetStatus excludedStatus);
    List<BetEntity> findByStatus(GameEnums.BetStatus status);
    List<BetEntity> findTop30ByStatusOrderByCreatedAtDesc(GameEnums.BetStatus status);
}
