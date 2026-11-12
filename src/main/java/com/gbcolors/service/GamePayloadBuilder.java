package com.gbcolors.service;

import com.gbcolors.config.EnvLoader;
import com.gbcolors.model.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Converts JPA entities into frontend-compatible JSON payload maps.
 */
@Component
public class GamePayloadBuilder {

    private final GameRuleEngine ruleEngine;

    public GamePayloadBuilder(GameRuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    public Map<String, Object> sanitizeUser(
            UserEntity u,
            List<BetEntity> betHistory,
            List<TransactionEntity> txs,
            List<WithdrawRequestEntity> withdrawRequests,
            List<DepositRequestEntity> depositRequests,
            List<NotificationEntity> unreadNotifications
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", u.getUserId());
        map.put("email", u.getEmail());
        map.put("username", u.getUsername());
        map.put("vipLevel", u.getVipLevel());
        map.put("demoBalance", toLong(u.getDemoBalance()));
        map.put("realBalance", toLong(u.getRealBalance()));
        map.put("mode", u.getActiveMode().name());
        map.put("totalWon", toLong(u.getTotalWon()));
        map.put("totalLost", toLong(u.getTotalLost()));
        map.put("totalRecharged", toLong(u.getTotalRecharged()));
        map.put("totalWithdrawn", toLong(u.getTotalWithdrawn()));
        map.put("blockType", u.getBlockType().name());
        map.put("blockedUntil", u.getBlockedUntil() != null ? u.getBlockedUntil().toEpochMilli() : null);
        map.put("blockReason", u.getBlockReason());
        map.put("betHistory", betHistory.stream().map(this::toBetLogMap).toList());
        map.put("transactions", txs.stream().map(this::toTxMap).toList());
        map.put("withdrawRequests", withdrawRequests.stream().map(this::toWithdrawReqMap).toList());
        map.put("depositRequests", depositRequests.stream().map(this::toDepositReqMap).toList());
        map.put("unreadNotifications", unreadNotifications.stream().map(this::toNotificationMap).toList());
        return map;
    }

    public Map<String, Object> toActiveBetMap(BetEntity b, String username) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", b.getId());
        map.put("email", b.getEmail());
        map.put("username", username);
        map.put("period", b.getPeriod());
        map.put("type", b.getBetType().name());
        map.put("choice", b.getChoice());
        map.put("amount", toLong(b.getAmount()));
        map.put("mode", b.getMode().name());
        map.put("status", b.getStatus().name());
        map.put("createdAt", toEpoch(b.getCreatedAt()));
        return map;
    }

    public Map<String, Object> toBetLogMap(BetEntity b) {
        Integer resNum = b.getResultNumber();
        GameRuleEngine.OutcomeProps props = resNum != null
                ? ruleEngine.getOutcomeProps(resNum)
                : new GameRuleEngine.OutcomeProps(List.of(), "");
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", b.getId());
        map.put("period", b.getPeriod());
        map.put("email", b.getEmail());
        map.put("type", b.getBetType().name());
        map.put("choice", b.getChoice());
        map.put("amount", toLong(b.getAmount()));
        map.put("mode", b.getMode().name());
        map.put("win", b.getStatus() == GameEnums.BetStatus.WON);
        map.put("payout", toLong(b.getPayout()));
        map.put("netProfit", toLong(b.getNetProfit()));
        map.put("resultNumber", resNum);
        map.put("resultColors", props.colors());
        map.put("resultSize", props.size());
        map.put("status", b.getStatus().name());
        map.put("timestamp", toEpoch(b.getCreatedAt()));
        return map;
    }

    public Map<String, Object> toTxMap(TransactionEntity t) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", t.getId());
        map.put("type", t.getTxType().name());
        map.put("method", t.getMethod());
        map.put("account", t.getAccountOrUtr());
        map.put("utr", t.getAccountOrUtr());
        map.put("amount", toLong(t.getAmount()));
        map.put("status", t.getStatus());
        map.put("timestamp", toEpoch(t.getCreatedAt()));
        return map;
    }

    public Map<String, Object> toRoundMap(GameRoundEntity r) {
        List<String> colorList = r.getColors() == null || r.getColors().isBlank()
                ? List.of()
                : Arrays.stream(r.getColors().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("period", r.getPeriod());
        map.put("number", r.getWinningNumber());
        map.put("colors", colorList);
        map.put("size", r.getSize());
        map.put("totalCollected", toLong(r.getTotalCollected()));
        map.put("totalPayout", toLong(r.getTotalPayout()));
        map.put("houseProfit", toLong(r.getHouseProfit()));
        map.put("timestamp", toEpoch(r.getCreatedAt()));
        return map;
    }

    public Map<String, Object> toWithdrawReqMap(WithdrawRequestEntity w) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", w.getId());
        map.put("email", w.getEmail());
        map.put("userId", w.getUserId());
        map.put("username", w.getUsername());
        map.put("amount", toLong(w.getAmount()));
        map.put("accountDetails", w.getAccountDetails());
        map.put("status", w.getStatus().name());
        map.put("requestedAt", toEpoch(w.getRequestedAt()));
        map.put("processedAt", w.getProcessedAt() != null ? w.getProcessedAt().toEpochMilli() : null);
        return map;
    }

    public Map<String, Object> toDepositReqMap(DepositRequestEntity d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", d.getId());
        map.put("email", d.getEmail());
        map.put("userId", d.getUserId());
        map.put("username", d.getUsername());
        map.put("amount", toLong(d.getAmount()));
        map.put("method", d.getMethod());
        map.put("utrOrPaymentId", d.getUtrOrPaymentId());
        map.put("orderId", d.getOrderId());
        map.put("status", d.getStatus().name());
        map.put("adminNote", d.getAdminNote());
        map.put("requestedAt", toEpoch(d.getRequestedAt()));
        map.put("processedAt", d.getProcessedAt() != null ? d.getProcessedAt().toEpochMilli() : null);
        return map;
    }

    public Map<String, Object> toNotificationMap(NotificationEntity n) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", n.getId());
        map.put("type", n.getType().name());
        map.put("title", n.getTitle());
        map.put("message", n.getMessage());
        map.put("amount", n.getAmount() != null ? toLong(n.getAmount()) : null);
        map.put("referenceId", n.getReferenceId());
        map.put("isRead", n.isRead());
        map.put("createdAt", toEpoch(n.getCreatedAt()));
        return map;
    }

    public Map<String, Object> buildAdminAnalyticsPayload(
            String currentPeriod,
            int timeLeft,
            String status,
            Integer forcedNextResult,
            Integer lockedWinningNumber,
            PlatformSettingsEntity settings,
            List<BetEntity> currentBets,
            List<UserEntity> allUsers,
            List<WithdrawRequestEntity> withdrawRequests,
            List<DepositRequestEntity> depositRequests,
            List<Map<String, Object>> roundHistory
    ) {
        Map<String, Long> colorTotals = new LinkedHashMap<>();
        colorTotals.put("GREEN", 0L);
        colorTotals.put("RED", 0L);
        colorTotals.put("VIOLET", 0L);

        Map<String, Long> sizeTotals = new LinkedHashMap<>();
        sizeTotals.put("BIG", 0L);
        sizeTotals.put("SMALL", 0L);

        Map<String, Long> numberTotals = new LinkedHashMap<>();
        for (int i = 0; i <= 9; i++) {
            numberTotals.put(String.valueOf(i), 0L);
        }

        long totalBetAmount = 0L;
        long realBetAmount = 0L;
        long demoBetAmount = 0L;

        for (BetEntity bet : currentBets) {
            long amt = toLong(bet.getAmount());
            totalBetAmount += amt;
            if (bet.getMode() == GameEnums.ActiveMode.REAL) {
                realBetAmount += amt;
            } else {
                demoBetAmount += amt;
            }
            if (bet.getBetType() == GameEnums.BetType.COLOR) {
                colorTotals.merge(bet.getChoice(), amt, Long::sum);
            } else if (bet.getBetType() == GameEnums.BetType.SIZE) {
                sizeTotals.merge(bet.getChoice(), amt, Long::sum);
            } else if (bet.getBetType() == GameEnums.BetType.NUMBER) {
                numberTotals.merge(bet.getChoice(), amt, Long::sum);
            }
        }

        GameRuleEngine.ProjectionSummary summary = ruleEngine.computeRoundProjections(
                currentBets,
                settings.getAdminMinProfitPercent()
        );

        Instant now = Instant.now();
        List<Map<String, Object>> usersList = new ArrayList<>();
        long totalUsersRealWallet = 0L;
        for (UserEntity u : allUsers) {
            long realBal = toLong(u.getRealBalance());
            totalUsersRealWallet += realBal;

            GameEnums.BlockType effBlock = u.getBlockType();
            if (effBlock == GameEnums.BlockType.TEMPORARY
                    && (u.getBlockedUntil() == null || !u.getBlockedUntil().isAfter(now))) {
                effBlock = GameEnums.BlockType.NONE;
            }

            Map<String, Object> uMap = new LinkedHashMap<>();
            uMap.put("userId", u.getUserId());
            uMap.put("email", u.getEmail());
            uMap.put("username", u.getUsername());
            uMap.put("realBalance", realBal);
            uMap.put("demoBalance", toLong(u.getDemoBalance()));
            uMap.put("totalWon", toLong(u.getTotalWon()));
            uMap.put("totalLost", toLong(u.getTotalLost()));
            uMap.put("totalRecharged", toLong(u.getTotalRecharged()));
            uMap.put("totalWithdrawn", toLong(u.getTotalWithdrawn()));
            uMap.put("lastRechargeAt", u.getLastRechargeAt() != null ? u.getLastRechargeAt().toEpochMilli() : null);
            uMap.put("lastRechargeAmt", toLong(u.getLastRechargeAmt()));
            uMap.put("lastWithdrawAt", u.getLastWithdrawAt() != null ? u.getLastWithdrawAt().toEpochMilli() : null);
            uMap.put("lastWithdrawAmt", toLong(u.getLastWithdrawAmt()));
            uMap.put("blockType", effBlock.name());
            uMap.put("blockedUntil", effBlock == GameEnums.BlockType.TEMPORARY && u.getBlockedUntil() != null
                    ? u.getBlockedUntil().toEpochMilli() : null);
            uMap.put("blockReason", effBlock != GameEnums.BlockType.NONE ? u.getBlockReason() : null);
            uMap.put("createdAt", toEpoch(u.getCreatedAt()));
            usersList.add(uMap);
        }

        long pendingWithdrawCount = withdrawRequests.stream()
                .filter(w -> w.getStatus() == GameEnums.WithdrawStatus.PENDING)
                .count();
        long pendingDepositCount = depositRequests.stream()
                .filter(d -> d.getStatus() == GameEnums.DepositStatus.PENDING)
                .count();

        Map<String, Object> finStats = new LinkedHashMap<>();
        finStats.put("totalHouseProfit", toLong(settings.getTotalHouseProfit()));
        finStats.put("totalBetsCollectedReal", toLong(settings.getTotalBetsCollectedReal()));
        finStats.put("totalPayoutsPaidReal", toLong(settings.getTotalPayoutsPaidReal()));
        finStats.put("totalDemoCollected", toLong(settings.getTotalDemoCollected()));
        finStats.put("totalDemoPayouts", toLong(settings.getTotalDemoPayouts()));
        finStats.put("totalRecharges", toLong(settings.getTotalRecharges()));
        finStats.put("totalWithdrawalsPaid", toLong(settings.getTotalWithdrawalsPaid()));
        finStats.put("totalUsersRealWallet", totalUsersRealWallet);
        finStats.put("totalRegisteredUsers", usersList.size());
        finStats.put("pendingWithdrawalsCount", pendingWithdrawCount);
        finStats.put("pendingDepositsCount", pendingDepositCount);

        Map<String, Object> liveTotals = new LinkedHashMap<>();
        liveTotals.put("colors", colorTotals);
        liveTotals.put("sizes", sizeTotals);
        liveTotals.put("numbers", numberTotals);
        liveTotals.put("totalBetAmount", totalBetAmount);
        liveTotals.put("realBetAmount", realBetAmount);
        liveTotals.put("demoBetAmount", demoBetAmount);

        boolean smtpConfigured = settings.getSmtpUser() != null && !settings.getSmtpUser().isBlank()
                && settings.getSmtpPass() != null && !settings.getSmtpPass().isBlank();

        Map<String, Object> smtpStatus = new LinkedHashMap<>();
        smtpStatus.put("adminEmail", settings.getAdminEmail());
        smtpStatus.put("host", settings.getSmtpHost());
        smtpStatus.put("port", settings.getSmtpPort());
        smtpStatus.put("authUser", settings.getSmtpUser());
        smtpStatus.put("fromName", settings.getSmtpFromName());
        smtpStatus.put("isConfigured", smtpConfigured);

        boolean razorpayEnabled = settings.getRazorpayKeyId() != null && !settings.getRazorpayKeyId().isBlank()
                && settings.getRazorpayKeySecret() != null && !settings.getRazorpayKeySecret().isBlank();
        Map<String, Object> paymentConfig = new LinkedHashMap<>();
        paymentConfig.put("upiId", settings.getUpiId());
        paymentConfig.put("upiPayeeName", settings.getUpiPayeeName());
        paymentConfig.put("razorpayKeyId", settings.getRazorpayKeyId() != null ? settings.getRazorpayKeyId() : "");
        paymentConfig.put("razorpayEnabled", razorpayEnabled);

        Map<String, Object> upcomingResult = null;
        if ("LOCKED".equals(status) && lockedWinningNumber != null) {
            GameRuleEngine.OutcomeProps props = ruleEngine.getOutcomeProps(lockedWinningNumber);
            long realPayout = 0L;
            long demoPayout = 0L;
            int winnersCount = 0;
            int realWinnersCount = 0;
            for (BetEntity b : currentBets) {
                GameRuleEngine.BetEvalResult eval = ruleEngine.evaluateBetOutcome(b, lockedWinningNumber);
                if (eval.win()) {
                    winnersCount++;
                    if (b.getMode() == GameEnums.ActiveMode.REAL) {
                        realWinnersCount++;
                    }
                }
                if (b.getMode() == GameEnums.ActiveMode.REAL) {
                    realPayout += eval.payout();
                } else {
                    demoPayout += eval.payout();
                }
            }
            boolean hasReal = realBetAmount > 0;
            long poolCollected = hasReal ? realBetAmount : totalBetAmount;
            long poolPayout = hasReal ? realPayout : demoPayout;
            upcomingResult = new LinkedHashMap<>();
            upcomingResult.put("period", currentPeriod);
            upcomingResult.put("number", lockedWinningNumber);
            upcomingResult.put("colors", props.colors());
            upcomingResult.put("size", props.size());
            upcomingResult.put("poolType", hasReal ? "REAL" : (totalBetAmount > 0 ? "DEMO" : "NONE"));
            upcomingResult.put("totalCollected", poolCollected);
            upcomingResult.put("totalPayout", poolPayout);
            upcomingResult.put("houseProfit", poolCollected - poolPayout);
            upcomingResult.put("realCollected", realBetAmount);
            upcomingResult.put("realPayout", realPayout);
            upcomingResult.put("realHouseProfit", realBetAmount - realPayout);
            upcomingResult.put("demoCollected", demoBetAmount);
            upcomingResult.put("demoPayout", demoPayout);
            upcomingResult.put("winnersCount", hasReal ? realWinnersCount : winnersCount);
            upcomingResult.put("modeUsed", forcedNextResult != null ? "MANUAL" : settings.getOutcomeMode().name());
            upcomingResult.put("revealInSeconds", timeLeft);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("period", currentPeriod);
        payload.put("timeLeft", timeLeft);
        payload.put("status", status);
        payload.put("cycleDurationSeconds", settings.getCycleDurationSeconds());
        payload.put("betCloseSeconds", settings.getBetCloseSeconds());
        payload.put("adminMinProfitPercent", settings.getAdminMinProfitPercent());
        payload.put("requiredMinProfit", summary.requiredMinProfit());
        payload.put("outcomeMode", settings.getOutcomeMode().name());
        payload.put("forcedNextResult", forcedNextResult);
        payload.put("upcomingResult", upcomingResult);
        payload.put("mysqlConnected", EnvLoader.isMysqlConnected());
        payload.put("liveTotals", liveTotals);
        payload.put("projections", summary.projections());
        payload.put("bestCandidates", summary.bestCandidates().stream().map(GameRuleEngine.NumberProjection::number).toList());
        payload.put("projectedMaxProfit", summary.maxProfit());
        payload.put("targetMode", summary.targetMode());
        payload.put("financialStats", finStats);
        payload.put("users", usersList);
        payload.put("withdrawRequests", withdrawRequests.stream().map(this::toWithdrawReqMap).toList());
        payload.put("depositRequests", depositRequests.stream().map(this::toDepositReqMap).toList());
        payload.put("history", roundHistory);
        payload.put("smtpStatus", smtpStatus);
        payload.put("paymentConfig", paymentConfig);
        return payload;
    }

    private long toLong(BigDecimal val) {
        return val == null ? 0L : val.longValue();
    }

    private long toEpoch(Instant inst) {
        return inst == null ? System.currentTimeMillis() : inst.toEpochMilli();
    }
}
