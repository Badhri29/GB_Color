package com.gbcolors.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * User bet placement and settlement ledger.
 */
@Entity
@Table(name = "bets", indexes = {
        @Index(name = "idx_bets_email", columnList = "email"),
        @Index(name = "idx_bets_period", columnList = "period")
})
public class BetEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "period", nullable = false, length = 32)
    private String period;

    @Column(name = "email", nullable = false, length = 191)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "bet_type", nullable = false, length = 16)
    private GameEnums.BetType betType;

    @Column(name = "choice", nullable = false, length = 20)
    private String choice;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private GameEnums.ActiveMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GameEnums.BetStatus status = GameEnums.BetStatus.ACTIVE;

    @Column(name = "payout", nullable = false, precision = 12, scale = 2)
    private BigDecimal payout = BigDecimal.ZERO;

    @Column(name = "net_profit", nullable = false, precision = 12, scale = 2)
    private BigDecimal netProfit = BigDecimal.ZERO;

    @Column(name = "result_number")
    private Integer resultNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public GameEnums.BetType getBetType() { return betType; }
    public void setBetType(GameEnums.BetType betType) { this.betType = betType; }

    public String getChoice() { return choice; }
    public void setChoice(String choice) { this.choice = choice; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public GameEnums.ActiveMode getMode() { return mode; }
    public void setMode(GameEnums.ActiveMode mode) { this.mode = mode; }

    public GameEnums.BetStatus getStatus() { return status; }
    public void setStatus(GameEnums.BetStatus status) { this.status = status; }

    public BigDecimal getPayout() { return payout; }
    public void setPayout(BigDecimal payout) { this.payout = payout; }

    public BigDecimal getNetProfit() { return netProfit; }
    public void setNetProfit(BigDecimal netProfit) { this.netProfit = netProfit; }

    public Integer getResultNumber() { return resultNumber; }
    public void setResultNumber(Integer resultNumber) { this.resultNumber = resultNumber; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
