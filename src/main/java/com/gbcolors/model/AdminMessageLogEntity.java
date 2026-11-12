package com.gbcolors.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Audit feed of broadcast and user-specific messages sent from the Admin Notification Chat Box.
 */
@Entity
@Table(name = "admin_message_logs")
public class AdminMessageLogEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "scope", nullable = false, length = 16)
    private String scope = "ALL"; // ALL or USER

    @Column(name = "target_email", length = 191)
    private String targetEmail;

    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    @Column(name = "title", nullable = false, length = 160)
    private String title;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "sent_by_email", nullable = false, length = 191)
    private String sentByEmail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public String getTargetEmail() { return targetEmail; }
    public void setTargetEmail(String targetEmail) { this.targetEmail = targetEmail; }

    public int getRecipientCount() { return recipientCount; }
    public void setRecipientCount(int recipientCount) { this.recipientCount = recipientCount; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getSentByEmail() { return sentByEmail; }
    public void setSentByEmail(String sentByEmail) { this.sentByEmail = sentByEmail; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
