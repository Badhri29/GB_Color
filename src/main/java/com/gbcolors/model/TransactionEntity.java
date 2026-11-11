package com.gbcolors.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Wallet deposit and withdrawal history entry.
 */
@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_tx_email", columnList = "email")
})
public class TransactionEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "email", nullable = false, length = 191)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_type", nullable = false, length = 16)
    private GameEnums.TxType txType;

    @Column(name = "method", length = 50)
    private String method;

    @Column(name = "account_or_utr", length = 191)
    private String accountOrUtr;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "status", nullable = false, length = 64)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public GameEnums.TxType getTxType() { return txType; }
    public void setTxType(GameEnums.TxType txType) { this.txType = txType; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getAccountOrUtr() { return accountOrUtr; }
    public void setAccountOrUtr(String accountOrUtr) { this.accountOrUtr = accountOrUtr; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
