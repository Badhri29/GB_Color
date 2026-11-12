package com.gbcolors.repository;

import com.gbcolors.model.DepositRequestEntity;
import com.gbcolors.model.GameEnums;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DepositRequestRepository extends JpaRepository<DepositRequestEntity, String> {
    List<DepositRequestEntity> findAllByOrderByRequestedAtDesc();
    List<DepositRequestEntity> findTop50ByEmailOrderByRequestedAtDesc(String email);
    Optional<DepositRequestEntity> findByOrderId(String orderId);

    @Query("SELECT COUNT(d) > 0 FROM DepositRequestEntity d WHERE d.utrOrPaymentId = :ref AND d.status <> :excludedStatus")
    boolean existsActiveRef(@Param("ref") String ref, @Param("excludedStatus") GameEnums.DepositStatus excludedStatus);
}
