package com.gbcolors.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Admin review queue for real-money withdrawal requests.
 */
@Entity
@Table(name = "withdraw_requests", indexes = {
        @Index(name = "idx_wth_status", columnList = "status")
})
public class WithdrawRequestEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "email", nullable = false, length = 191)
    private String email;

    @Column(name = "user_id", nullable = false, length = 24)
    private String userId;

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "account_details", nullable = false, length = 255)
    private String accountDetails;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GameEnums.WithdrawStatus status = GameEnums.WithdrawStatus.PENDING;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getAccountDetails() { return accountDetails; }
    public void setAccountDetails(String accountDetails) { this.accountDetails = accountDetails; }

    public GameEnums.WithdrawStatus getStatus() { return status; }
    public void setStatus(GameEnums.WithdrawStatus status) { this.status = status; }

    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }

    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
