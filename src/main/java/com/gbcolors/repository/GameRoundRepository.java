package com.gbcolors.repository;

import com.gbcolors.model.GameRoundEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GameRoundRepository extends JpaRepository<GameRoundEntity, Long> {
    List<GameRoundEntity> findTop50ByOrderByIdDesc();
    boolean existsByPeriod(String period);
}
