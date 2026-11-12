package com.gbcolors.model;

/**
 * Shared domain enumerations persisted as strings in MySQL/H2.
 */
public final class GameEnums {

    private GameEnums() {}

    public enum ActiveMode {
        DEMO, REAL
    }

    public enum SessionRole {
        USER, ADMIN
    }

    public enum BetType {
        COLOR, NUMBER, SIZE
    }

    public enum BetStatus {
        ACTIVE, WON, LOST, CANCELLED
    }

    public enum TxType {
        DEPOSIT, WITHDRAWAL
    }

    public enum WithdrawStatus {
        PENDING, PAID, REJECTED
    }

    public enum OutcomeMode {
        SMART_PROFIT, FAIR_RANDOM, MANUAL
    }

    public enum BlockType {
        NONE, TEMPORARY, PERMANENT
    }

    public enum DepositStatus {
        PENDING, APPROVED, REJECTED
    }

    public enum NotificationType {
        WITHDRAW_PAID, WITHDRAW_REJECTED, DEPOSIT_APPROVED, DEPOSIT_REJECTED, ADMIN_BROADCAST, ADMIN_DIRECT, INFO
    }
}
