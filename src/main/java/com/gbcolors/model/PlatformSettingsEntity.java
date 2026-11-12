package com.gbcolors.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * Single-row (id=1) platform financial totals, engine mode, and SMTP configuration.
 */
@Entity
@Table(name = "platform_settings")
public class PlatformSettingsEntity {

    @Id
    @Column(name = "id")
    private Integer id = 1;

    @Column(name = "admin_email", nullable = false, length = 191)
    private String adminEmail;

    @Column(name = "admin_password_hash", nullable = false, length = 255)
    private String adminPasswordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome_mode", nullable = false, length = 32)
    private GameEnums.OutcomeMode outcomeMode = GameEnums.OutcomeMode.SMART_PROFIT;

    @Column(name = "total_house_profit", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalHouseProfit = BigDecimal.ZERO;

    @Column(name = "total_bets_collected_real", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalBetsCollectedReal = BigDecimal.ZERO;

    @Column(name = "total_payouts_paid_real", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalPayoutsPaidReal = BigDecimal.ZERO;

    @Column(name = "total_demo_collected", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalDemoCollected = BigDecimal.ZERO;

    @Column(name = "total_demo_payouts", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalDemoPayouts = BigDecimal.ZERO;

    @Column(name = "total_recharges", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalRecharges = BigDecimal.ZERO;

    @Column(name = "total_withdrawals_paid", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalWithdrawalsPaid = BigDecimal.ZERO;

    @Column(name = "round_counter", nullable = false)
    private int roundCounter = 26;

    @Column(name = "smtp_host", nullable = false, length = 100)
    private String smtpHost = "smtp.gmail.com";

    @Column(name = "smtp_port", nullable = false)
    private int smtpPort = 587;

    @Column(name = "smtp_user", length = 191)
    private String smtpUser;

    @Column(name = "smtp_pass", length = 255)
    private String smtpPass;

    @Column(name = "smtp_from_name", nullable = false, length = 100)
    private String smtpFromName = "GB_Colors";

    @Column(name = "cycle_duration_seconds")
    private Integer cycleDurationSeconds = 30;

    @Column(name = "bet_close_seconds")
    private Integer betCloseSeconds = 5;

    @Column(name = "admin_min_profit_percent")
    private Integer adminMinProfitPercent = 15;

    @Column(name = "upi_id", length = 120)
    private String upiId = "badhrinarayanan79-3@okhdfcbank";

    @Column(name = "upi_payee_name", length = 120)
    private String upiPayeeName = "Badhri Narayanan";

    @Column(name = "razorpay_key_id", length = 128)
    private String razorpayKeyId;

    @Column(name = "razorpay_key_secret", length = 255)
    private String razorpayKeySecret;

    @Column(name = "min_deposit_amount")
    private Integer minDepositAmount = 100;

    @Column(name = "max_deposit_amount")
    private Integer maxDepositAmount = 50000;

    @Column(name = "min_withdraw_amount")
    private Integer minWithdrawAmount = 200;

    @Column(name = "max_withdraw_amount")
    private Integer maxWithdrawAmount = 50000;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getAdminEmail() { return adminEmail; }
    public void setAdminEmail(String adminEmail) { this.adminEmail = adminEmail; }

    public String getAdminPasswordHash() { return adminPasswordHash; }
    public void setAdminPasswordHash(String adminPasswordHash) { this.adminPasswordHash = adminPasswordHash; }

    public GameEnums.OutcomeMode getOutcomeMode() { return outcomeMode; }
    public void setOutcomeMode(GameEnums.OutcomeMode outcomeMode) { this.outcomeMode = outcomeMode; }

    public BigDecimal getTotalHouseProfit() { return totalHouseProfit; }
    public void setTotalHouseProfit(BigDecimal totalHouseProfit) { this.totalHouseProfit = totalHouseProfit; }

    public BigDecimal getTotalBetsCollectedReal() { return totalBetsCollectedReal; }
    public void setTotalBetsCollectedReal(BigDecimal totalBetsCollectedReal) { this.totalBetsCollectedReal = totalBetsCollectedReal; }

    public BigDecimal getTotalPayoutsPaidReal() { return totalPayoutsPaidReal; }
    public void setTotalPayoutsPaidReal(BigDecimal totalPayoutsPaidReal) { this.totalPayoutsPaidReal = totalPayoutsPaidReal; }

    public BigDecimal getTotalDemoCollected() { return totalDemoCollected; }
    public void setTotalDemoCollected(BigDecimal totalDemoCollected) { this.totalDemoCollected = totalDemoCollected; }

    public BigDecimal getTotalDemoPayouts() { return totalDemoPayouts; }
    public void setTotalDemoPayouts(BigDecimal totalDemoPayouts) { this.totalDemoPayouts = totalDemoPayouts; }

    public BigDecimal getTotalRecharges() { return totalRecharges; }
    public void setTotalRecharges(BigDecimal totalRecharges) { this.totalRecharges = totalRecharges; }

    public BigDecimal getTotalWithdrawalsPaid() { return totalWithdrawalsPaid; }
    public void setTotalWithdrawalsPaid(BigDecimal totalWithdrawalsPaid) { this.totalWithdrawalsPaid = totalWithdrawalsPaid; }

    public int getRoundCounter() { return roundCounter; }
    public void setRoundCounter(int roundCounter) { this.roundCounter = roundCounter; }

    public String getSmtpHost() { return smtpHost; }
    public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }

    public int getSmtpPort() { return smtpPort; }
    public void setSmtpPort(int smtpPort) { this.smtpPort = smtpPort; }

    public String getSmtpUser() { return smtpUser; }
    public void setSmtpUser(String smtpUser) { this.smtpUser = smtpUser; }

    public String getSmtpPass() { return smtpPass; }
    public void setSmtpPass(String smtpPass) { this.smtpPass = smtpPass; }

    public String getSmtpFromName() { return smtpFromName; }
    public void setSmtpFromName(String smtpFromName) { this.smtpFromName = smtpFromName; }

    public int getCycleDurationSeconds() {
        return cycleDurationSeconds != null && cycleDurationSeconds >= 15 ? cycleDurationSeconds : 30;
    }
    public void setCycleDurationSeconds(Integer cycleDurationSeconds) {
        this.cycleDurationSeconds = cycleDurationSeconds != null ? cycleDurationSeconds : 30;
    }

    public int getBetCloseSeconds() {
        int cycle = getCycleDurationSeconds();
        int close = betCloseSeconds != null && betCloseSeconds >= 2 ? betCloseSeconds : 5;
        return Math.min(close, Math.max(2, cycle - 1));
    }
    public void setBetCloseSeconds(Integer betCloseSeconds) {
        this.betCloseSeconds = betCloseSeconds != null ? betCloseSeconds : 5;
    }

    public int getAdminMinProfitPercent() {
        if (adminMinProfitPercent == null) return 15;
        return Math.max(0, Math.min(100, adminMinProfitPercent));
    }
    public void setAdminMinProfitPercent(Integer adminMinProfitPercent) {
        this.adminMinProfitPercent = adminMinProfitPercent != null ? Math.max(0, Math.min(100, adminMinProfitPercent)) : 15;
    }

    public String getUpiId() { return upiId != null && !upiId.isBlank() ? upiId : "badhrinarayanan79-3@okhdfcbank"; }
    public void setUpiId(String upiId) { this.upiId = upiId; }

    public String getUpiPayeeName() { return upiPayeeName != null && !upiPayeeName.isBlank() ? upiPayeeName : "Badhri Narayanan"; }
    public void setUpiPayeeName(String upiPayeeName) { this.upiPayeeName = upiPayeeName; }

    public String getRazorpayKeyId() { return razorpayKeyId; }
    public void setRazorpayKeyId(String razorpayKeyId) { this.razorpayKeyId = razorpayKeyId; }

    public String getRazorpayKeySecret() { return razorpayKeySecret; }
    public void setRazorpayKeySecret(String razorpayKeySecret) { this.razorpayKeySecret = razorpayKeySecret; }

    public int getMinDepositAmount() {
        return minDepositAmount != null && minDepositAmount >= 10 ? minDepositAmount : 100;
    }
    public void setMinDepositAmount(Integer minDepositAmount) {
        this.minDepositAmount = minDepositAmount != null && minDepositAmount >= 10 ? minDepositAmount : 100;
    }

    public int getMaxDepositAmount() {
        int min = getMinDepositAmount();
        return maxDepositAmount != null && maxDepositAmount >= min ? maxDepositAmount : Math.max(min, 50000);
    }
    public void setMaxDepositAmount(Integer maxDepositAmount) {
        int min = getMinDepositAmount();
        this.maxDepositAmount = maxDepositAmount != null && maxDepositAmount >= min ? maxDepositAmount : Math.max(min, 50000);
    }

    public int getMinWithdrawAmount() {
        return minWithdrawAmount != null && minWithdrawAmount >= 10 ? minWithdrawAmount : 200;
    }
    public void setMinWithdrawAmount(Integer minWithdrawAmount) {
        this.minWithdrawAmount = minWithdrawAmount != null && minWithdrawAmount >= 10 ? minWithdrawAmount : 200;
    }

    public int getMaxWithdrawAmount() {
        int min = getMinWithdrawAmount();
        return maxWithdrawAmount != null && maxWithdrawAmount >= min ? maxWithdrawAmount : Math.max(min, 50000);
    }
    public void setMaxWithdrawAmount(Integer maxWithdrawAmount) {
        int min = getMinWithdrawAmount();
        this.maxWithdrawAmount = maxWithdrawAmount != null && maxWithdrawAmount >= min ? maxWithdrawAmount : Math.max(min, 50000);
    }
}
