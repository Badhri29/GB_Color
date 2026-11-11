package com.gbcolors.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Bearer authentication session token stored in database.
 */
@Entity
@Table(name = "sessions")
public class SessionEntity {

    @Id
    @Column(name = "token", length = 128)
    private String token;

    @Column(name = "email", nullable = false, length = 191)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private GameEnums.SessionRole role = GameEnums.SessionRole.USER;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public GameEnums.SessionRole getRole() { return role; }
    public void setRole(GameEnums.SessionRole role) { this.role = role; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
