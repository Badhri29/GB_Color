package com.gbcolors.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Registered player account and wallets (Demo & Real).
 */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true, length = 24)
    private String userId;

    @Column(name = "email", nullable = false, unique = true, length = 191)
    private String email;

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "vip_level", nullable = false)
    private int vipLevel = 1;

    @Column(name = "demo_balance", nullable = false, precision = 12, scale = 2)
    private BigDecimal demoBalance = BigDecimal.valueOf(1000);

    @Column(name = "real_balance", nullable = false, precision = 12, scale = 2)
    private BigDecimal realBalance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "active_mode", nullable = false, length = 16)
    private GameEnums.ActiveMode activeMode = GameEnums.ActiveMode.DEMO;

    @Column(name = "total_won", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalWon = BigDecimal.ZERO;

    @Column(name = "total_lost", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalLost = BigDecimal.ZERO;

    @Column(name = "total_recharged", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalRecharged = BigDecimal.ZERO;

    @Column(name = "total_withdrawn", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalWithdrawn = BigDecimal.ZERO;

    @Column(name = "last_recharge_at")
    private Instant lastRechargeAt;

    @Column(name = "last_recharge_amt", nullable = false, precision = 12, scale = 2)
    private BigDecimal lastRechargeAmt = BigDecimal.ZERO;

    @Column(name = "last_withdraw_at")
    private Instant lastWithdrawAt;

    @Column(name = "last_withdraw_amt", nullable = false, precision = 12, scale = 2)
    private BigDecimal lastWithdrawAmt = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public int getVipLevel() { return vipLevel; }
    public void setVipLevel(int vipLevel) { this.vipLevel = vipLevel; }

    public BigDecimal getDemoBalance() { return demoBalance; }
    public void setDemoBalance(BigDecimal demoBalance) { this.demoBalance = demoBalance; }

    public BigDecimal getRealBalance() { return realBalance; }
    public void setRealBalance(BigDecimal realBalance) { this.realBalance = realBalance; }

    public GameEnums.ActiveMode getActiveMode() { return activeMode; }
    public void setActiveMode(GameEnums.ActiveMode activeMode) { this.activeMode = activeMode; }

    public BigDecimal getTotalWon() { return totalWon; }
    public void setTotalWon(BigDecimal totalWon) { this.totalWon = totalWon; }

    public BigDecimal getTotalLost() { return totalLost; }
    public void setTotalLost(BigDecimal totalLost) { this.totalLost = totalLost; }

    public BigDecimal getTotalRecharged() { return totalRecharged; }
    public void setTotalRecharged(BigDecimal totalRecharged) { this.totalRecharged = totalRecharged; }

    public BigDecimal getTotalWithdrawn() { return totalWithdrawn; }
    public void setTotalWithdrawn(BigDecimal totalWithdrawn) { this.totalWithdrawn = totalWithdrawn; }

    public Instant getLastRechargeAt() { return lastRechargeAt; }
    public void setLastRechargeAt(Instant lastRechargeAt) { this.lastRechargeAt = lastRechargeAt; }

    public BigDecimal getLastRechargeAmt() { return lastRechargeAmt; }
    public void setLastRechargeAmt(BigDecimal lastRechargeAmt) { this.lastRechargeAmt = lastRechargeAmt; }

    public Instant getLastWithdrawAt() { return lastWithdrawAt; }
    public void setLastWithdrawAt(Instant lastWithdrawAt) { this.lastWithdrawAt = lastWithdrawAt; }

    public BigDecimal getLastWithdrawAmt() { return lastWithdrawAmt; }
    public void setLastWithdrawAmt(BigDecimal lastWithdrawAmt) { this.lastWithdrawAmt = lastWithdrawAmt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
