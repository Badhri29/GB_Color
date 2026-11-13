package com.gbcolors.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Deposit / Recharge request tracking both UPI UTR manual verification and Razorpay gateway orders.
 */
@Entity
@Table(name = "deposit_requests", indexes = {
        @Index(name = "idx_dep_status", columnList = "status"),
        @Index(name = "idx_dep_email", columnList = "email")
})
public class DepositRequestEntity {

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

    @Column(name = "method", nullable = false, length = 32)
    private String method = "UPI";

    @Column(name = "utr_or_payment_id", length = 191)
    private String utrOrPaymentId;

    @Column(name = "order_id", length = 128)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GameEnums.DepositStatus status = GameEnums.DepositStatus.PENDING;

    @Column(name = "admin_note", length = 255)
    private String adminNote;

    @Lob
    @Column(name = "proof_image", columnDefinition = "LONGTEXT")
    private String proofImage;

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

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getUtrOrPaymentId() { return utrOrPaymentId; }
    public void setUtrOrPaymentId(String utrOrPaymentId) { this.utrOrPaymentId = utrOrPaymentId; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public GameEnums.DepositStatus getStatus() { return status; }
    public void setStatus(GameEnums.DepositStatus status) { this.status = status; }

    public String getAdminNote() { return adminNote; }
    public void setAdminNote(String adminNote) { this.adminNote = adminNote; }

    public String getProofImage() { return proofImage; }
    public void setProofImage(String proofImage) { this.proofImage = proofImage; }

    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }

    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
