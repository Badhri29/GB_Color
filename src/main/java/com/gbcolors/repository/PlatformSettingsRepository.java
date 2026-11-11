package com.gbcolors.repository;

import com.gbcolors.model.PlatformSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformSettingsRepository extends JpaRepository<PlatformSettingsEntity, Integer> {
}
