package com.gbcolors.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Completed 30-second game round result.
 */
@Entity
@Table(name = "game_rounds")
public class GameRoundEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period", nullable = false, unique = true, length = 32)
    private String period;

    @Column(name = "winning_number", nullable = false)
    private int winningNumber;

    @Column(name = "colors", nullable = false, length = 64)
    private String colors;

    @Column(name = "size", nullable = false, length = 16)
    private String size;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }

    public int getWinningNumber() { return winningNumber; }
    public void setWinningNumber(int winningNumber) { this.winningNumber = winningNumber; }

    public String getColors() { return colors; }
    public void setColors(String colors) { this.colors = colors; }

    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
