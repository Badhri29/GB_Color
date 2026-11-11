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

    public Map<String, Object> sanitizeUser(UserEntity u, List<BetEntity> betHistory, List<TransactionEntity> txs) {
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
        map.put("betHistory", betHistory.stream().map(this::toBetLogMap).toList());
        map.put("transactions", txs.stream().map(this::toTxMap).toList());
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

    public Map<String, Object> buildAdminAnalyticsPayload(
            String currentPeriod,
            int timeLeft,
            String status,
            Integer forcedNextResult,
            PlatformSettingsEntity settings,
            List<BetEntity> currentBets,
            List<UserEntity> allUsers,
            List<WithdrawRequestEntity> withdrawRequests
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

        GameRuleEngine.ProjectionSummary summary = ruleEngine.computeRoundProjections(currentBets);

        List<Map<String, Object>> usersList = new ArrayList<>();
        long totalUsersRealWallet = 0L;
        for (UserEntity u : allUsers) {
            long realBal = toLong(u.getRealBalance());
            totalUsersRealWallet += realBal;
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
            uMap.put("createdAt", toEpoch(u.getCreatedAt()));
            usersList.add(uMap);
        }

        long pendingCount = withdrawRequests.stream()
                .filter(w -> w.getStatus() == GameEnums.WithdrawStatus.PENDING)
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
        finStats.put("pendingWithdrawalsCount", pendingCount);

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

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("period", currentPeriod);
        payload.put("timeLeft", timeLeft);
        payload.put("status", status);
        payload.put("outcomeMode", settings.getOutcomeMode().name());
        payload.put("forcedNextResult", forcedNextResult);
        payload.put("mysqlConnected", EnvLoader.isMysqlConnected());
        payload.put("liveTotals", liveTotals);
        payload.put("projections", summary.projections());
        payload.put("bestCandidates", summary.bestCandidates().stream().map(GameRuleEngine.NumberProjection::number).toList());
        payload.put("projectedMaxProfit", summary.maxProfit());
        payload.put("targetMode", summary.targetMode());
        payload.put("financialStats", finStats);
        payload.put("users", usersList);
        payload.put("withdrawRequests", withdrawRequests.stream().map(this::toWithdrawReqMap).toList());
        payload.put("smtpStatus", smtpStatus);
        return payload;
    }

    private long toLong(BigDecimal val) {
        return val == null ? 0L : val.longValue();
    }

    private long toEpoch(Instant inst) {
        return inst == null ? System.currentTimeMillis() : inst.toEpochMilli();
    }
}
