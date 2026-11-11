package com.gbcolors.service;

import com.gbcolors.config.EnvLoader;
import com.gbcolors.model.*;
import com.gbcolors.repository.*;
import com.gbcolors.websocket.WebSocketHub;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates the 30-second countdown loop, round settlement, bet lifecycle,
 * wallet operations, and Admin analytics broadcasting.
 */
@Service
public class GameLoopService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReentrantLock stateLock = new ReentrantLock();
    private final TransactionTemplate txTemplate;
    private final UserRepository userRepo;
    private final GameRoundRepository roundRepo;
    private final BetRepository betRepo;
    private final TransactionRepository txRepo;
    private final WithdrawRequestRepository withdrawRepo;
    private final PlatformSettingsRepository settingsRepo;
    private final GameRuleEngine ruleEngine;
    private final GamePayloadBuilder payloadBuilder;
    private final PasswordService passwordService;
    private final WebSocketHub wsHub;

    @Value("${server.port:7070}")
    private int serverPort;

    @Value("${gbcolors.admin-email:gbcolor.admin@gmail.com}")
    private String envAdminEmail;

    @Value("${gbcolors.admin-default-password:Admin@123}")
    private String envAdminPass;

    @Value("${gbcolors.smtp.host:smtp.gmail.com}")
    private String envSmtpHost;

    @Value("${gbcolors.smtp.port:587}")
    private int envSmtpPort;

    @Value("${gbcolors.smtp.user:gbcolor.admin@gmail.com}")
    private String envSmtpUser;

    @Value("${gbcolors.smtp.pass:}")
    private String envSmtpPass;

    @Value("${gbcolors.smtp.from-name:GB_Colors}")
    private String envSmtpFromName;

    private int timeLeft = 30;
    private String currentStatus = "BETTING";
    private String currentPeriod = "";
    private Integer forcedNextResult = null;
    private final List<BetEntity> currentBets = new ArrayList<>();

    public GameLoopService(
            TransactionTemplate txTemplate,
            UserRepository userRepo,
            GameRoundRepository roundRepo,
            BetRepository betRepo,
            TransactionRepository txRepo,
            WithdrawRequestRepository withdrawRepo,
            PlatformSettingsRepository settingsRepo,
            GameRuleEngine ruleEngine,
            GamePayloadBuilder payloadBuilder,
            PasswordService passwordService,
            WebSocketHub wsHub
    ) {
        this.txTemplate = txTemplate;
        this.userRepo = userRepo;
        this.roundRepo = roundRepo;
        this.betRepo = betRepo;
        this.txRepo = txRepo;
        this.withdrawRepo = withdrawRepo;
        this.settingsRepo = settingsRepo;
        this.ruleEngine = ruleEngine;
        this.payloadBuilder = payloadBuilder;
        this.passwordService = passwordService;
        this.wsHub = wsHub;
    }

    @PostConstruct
    public void initOnBoot() {
        stateLock.lock();
        try {
            PlatformSettingsEntity settings = txTemplate.execute(status -> {
                PlatformSettingsEntity s = settingsRepo.findById(1).orElse(null);
                String cleanAdminEmail = envAdminEmail.trim().toLowerCase();
                if (s == null) {
                    s = new PlatformSettingsEntity();
                    s.setId(1);
                    s.setAdminEmail(cleanAdminEmail);
                    s.setAdminPasswordHash(passwordService.hashPassword(envAdminPass));
                    s.setOutcomeMode(GameEnums.OutcomeMode.SMART_PROFIT);
                    s.setRoundCounter(26);
                } else {
                    s.setAdminEmail(cleanAdminEmail);
                    if (s.getAdminPasswordHash() == null || !s.getAdminPasswordHash().startsWith("$2")) {
                        s.setAdminPasswordHash(passwordService.hashPassword(envAdminPass));
                    }
                }
                if (s.getSmtpHost() == null || s.getSmtpHost().isBlank()) s.setSmtpHost(envSmtpHost);
                if (s.getSmtpPort() <= 0) s.setSmtpPort(envSmtpPort);
                if (envSmtpUser != null && !envSmtpUser.isBlank()) s.setSmtpUser(envSmtpUser.trim());
                if (envSmtpPass != null && !envSmtpPass.isBlank()) s.setSmtpPass(envSmtpPass.replaceAll("\\s+", "").trim());
                if (envSmtpFromName != null && !envSmtpFromName.isBlank()) s.setSmtpFromName(envSmtpFromName.trim());

                s = settingsRepo.save(s);

                if (roundRepo.count() == 0) {
                    long baseMs = System.currentTimeMillis();
                    for (int i = 25; i >= 1; i--) {
                        int num = RANDOM.nextInt(10);
                        GameRuleEngine.OutcomeProps props = ruleEngine.getOutcomeProps(num);
                        String p = ruleEngine.generatePeriodString(i);
                        if (!roundRepo.existsByPeriod(p)) {
                            GameRoundEntity r = new GameRoundEntity();
                            r.setPeriod(p);
                            r.setWinningNumber(num);
                            r.setColors(String.join(",", props.colors()));
                            r.setSize(props.size());
                            r.setCreatedAt(Instant.ofEpochMilli(baseMs - (i * 30_000L)));
                            roundRepo.save(r);
                        }
                    }
                }
                return s;
            });

            currentPeriod = ruleEngine.generatePeriodString(settings.getRoundCounter());
            List<BetEntity> activeFromDb = betRepo.findByStatus(GameEnums.BetStatus.ACTIVE);
            currentBets.clear();
            for (BetEntity b : activeFromDb) {
                if (currentPeriod.equals(b.getPeriod())) {
                    currentBets.add(b);
                }
            }

            System.out.println("====================================================");
            System.out.println("🚀 GB_Colors Java Server booted cleanly!");
            System.out.println("🔗 Local URL: http://localhost:" + serverPort);
            System.out.println("📧 OTP Sender: " + settings.getSmtpUser());
            System.out.println("🔐 Admin Email: " + settings.getAdminEmail());
            System.out.println("🗄️  Database: " + (EnvLoader.isMysqlConnected()
                    ? "MYSQL CONNECTED (" + EnvLoader.getActiveDatabaseName() + ", Auto-Schema)"
                    : "LOCAL H2 EMBEDDED MODE (Set MYSQL_PASSWORD in .env for Workbench)"));
            System.out.println("====================================================");
        } finally {
            stateLock.unlock();
        }
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        Map<String, Object> tickPayload;
        Map<String, Object> adminAnalyticsSnapshot;
        Map<String, Object> roundResultBroadcast = null;
        List<Map.Entry<String, Map<String, Object>>> settledUserPayloads = null;

        stateLock.lock();
        try {
            timeLeft--;
            if (timeLeft <= 5 && timeLeft > 0) {
                currentStatus = "LOCKED";
            } else if (timeLeft <= 0) {
                SettlementResult settlement = settleRoundInsideTx();
                roundResultBroadcast = settlement.roundResultBroadcast();
                settledUserPayloads = settlement.settledUserPayloads();

                currentStatus = "BETTING";
                timeLeft = 30;
                currentBets.clear();
            }

            tickPayload = Map.of(
                    "period", currentPeriod,
                    "timeLeft", timeLeft,
                    "status", currentStatus,
                    "activeBetsCount", currentBets.size()
            );
            adminAnalyticsSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }

        if (settledUserPayloads != null) {
            for (var entry : settledUserPayloads) {
                wsHub.emitToUser(entry.getKey(), "bet_settled", entry.getValue());
            }
        }
        if (roundResultBroadcast != null) {
            wsHub.emitAll("round_result", roundResultBroadcast);
        }
        wsHub.emitAll("game_tick", tickPayload);
        wsHub.emitToAdmin("admin_live_update", adminAnalyticsSnapshot);
    }

    private record SettlementResult(
            Map<String, Object> roundResultBroadcast,
            List<Map.Entry<String, Map<String, Object>>> settledUserPayloads
    ) {}

    private SettlementResult settleRoundInsideTx() {
        List<Map.Entry<String, Map<String, Object>>> settledUserPayloads = new ArrayList<>();
        AtomicReference<GameRoundEntity> savedRoundRef = new AtomicReference<>();
        AtomicInteger winnersCountRef = new AtomicInteger(0);
        AtomicReference<List<Map<String, Object>>> latestHistoryRef = new AtomicReference<>(List.of());

        txTemplate.executeWithoutResult(status -> {
            PlatformSettingsEntity settings = loadOrInitSettings();
            int winningNumber;

            if (forcedNextResult != null && forcedNextResult >= 0 && forcedNextResult <= 9) {
                winningNumber = forcedNextResult;
                forcedNextResult = null;
                if (settings.getOutcomeMode() == GameEnums.OutcomeMode.MANUAL) {
                    settings.setOutcomeMode(GameEnums.OutcomeMode.SMART_PROFIT);
                }
            } else if (settings.getOutcomeMode() == GameEnums.OutcomeMode.SMART_PROFIT && !currentBets.isEmpty()) {
                GameRuleEngine.ProjectionSummary summary = ruleEngine.computeRoundProjections(currentBets);
                List<GameRuleEngine.NumberProjection> best = summary.bestCandidates();
                winningNumber = best.get(RANDOM.nextInt(best.size())).number();
            } else {
                winningNumber = RANDOM.nextInt(10);
            }

            while (roundRepo.existsByPeriod(currentPeriod)) {
                settings.setRoundCounter(settings.getRoundCounter() + 1);
                currentPeriod = ruleEngine.generatePeriodString(settings.getRoundCounter());
            }

            GameRuleEngine.OutcomeProps props = ruleEngine.getOutcomeProps(winningNumber);

            GameRoundEntity roundEntity = new GameRoundEntity();
            roundEntity.setPeriod(currentPeriod);
            roundEntity.setWinningNumber(winningNumber);
            roundEntity.setColors(String.join(",", props.colors()));
            roundEntity.setSize(props.size());
            roundEntity.setCreatedAt(Instant.now());
            savedRoundRef.set(roundRepo.save(roundEntity));

            int winnersCount = 0;

            for (BetEntity bet : currentBets) {
                GameRuleEngine.BetEvalResult eval = ruleEngine.evaluateBetOutcome(bet, winningNumber);
                BigDecimal amtBd = bet.getAmount();
                BigDecimal payoutBd = BigDecimal.valueOf(eval.payout());
                BigDecimal netProfitBd = eval.win() ? payoutBd.subtract(amtBd) : amtBd.negate();

                if (bet.getMode() == GameEnums.ActiveMode.REAL) {
                    settings.setTotalBetsCollectedReal(settings.getTotalBetsCollectedReal().add(amtBd));
                    settings.setTotalPayoutsPaidReal(settings.getTotalPayoutsPaidReal().add(payoutBd));
                    settings.setTotalHouseProfit(settings.getTotalBetsCollectedReal().subtract(settings.getTotalPayoutsPaidReal()));
                } else {
                    settings.setTotalDemoCollected(settings.getTotalDemoCollected().add(amtBd));
                    settings.setTotalDemoPayouts(settings.getTotalDemoPayouts().add(payoutBd));
                }

                bet.setStatus(eval.win() ? GameEnums.BetStatus.WON : GameEnums.BetStatus.LOST);
                bet.setPayout(payoutBd);
                bet.setNetProfit(netProfitBd);
                bet.setResultNumber(winningNumber);
                betRepo.save(bet);

                UserEntity user = userRepo.findByEmail(bet.getEmail()).orElse(null);
                if (user != null) {
                    if (eval.win()) {
                        winnersCount++;
                        if (bet.getMode() == GameEnums.ActiveMode.REAL) {
                            user.setRealBalance(user.getRealBalance().add(payoutBd));
                            user.setTotalWon(user.getTotalWon().add(payoutBd.subtract(amtBd)));
                        } else {
                            user.setDemoBalance(user.getDemoBalance().add(payoutBd));
                        }
                    } else if (bet.getMode() == GameEnums.ActiveMode.REAL) {
                        user.setTotalLost(user.getTotalLost().add(amtBd));
                    }
                    userRepo.save(user);

                    Map<String, Object> settledPayload = new LinkedHashMap<>();
                    settledPayload.put("bet", payloadBuilder.toBetLogMap(bet));
                    settledPayload.put("demoBalance", user.getDemoBalance().longValue());
                    settledPayload.put("realBalance", user.getRealBalance().longValue());
                    settledUserPayloads.add(Map.entry(bet.getEmail(), settledPayload));
                }
            }

            winnersCountRef.set(winnersCount);
            settings.setRoundCounter(settings.getRoundCounter() + 1);
            settingsRepo.save(settings);
            currentPeriod = ruleEngine.generatePeriodString(settings.getRoundCounter());
            latestHistoryRef.set(getHistoryTop25());
        });

        Map<String, Object> roundMap = payloadBuilder.toRoundMap(savedRoundRef.get());
        Map<String, Object> broadcast = new LinkedHashMap<>();
        broadcast.put("result", roundMap);
        broadcast.put("history", latestHistoryRef.get());
        broadcast.put("winnersCount", winnersCountRef.get());

        return new SettlementResult(broadcast, settledUserPayloads);
    }

    public PlatformSettingsEntity getPlatformSettings() {
        return txTemplate.execute(status -> loadOrInitSettings());
    }

    public Map<String, Object> getSanitizedUserPayload(String email) {
        return txTemplate.execute(status -> userRepo.findByEmail(email)
                .map(u -> buildSanitizedUser(u))
                .orElse(null));
    }

    public Map<String, Object> getInitialStateForUser(String email) {
        stateLock.lock();
        try {
            return txTemplate.execute(status -> {
                UserEntity user = userRepo.findByEmail(email).orElse(null);
                if (user == null) return null;

                Map<String, Object> userMap = buildSanitizedUser(user);
                List<Map<String, Object>> active = currentBets.stream()
                        .filter(b -> email.equals(b.getEmail()))
                        .map(b -> payloadBuilder.toActiveBetMap(b, user.getUsername()))
                        .toList();
                userMap.put("activeBets", active);

                Map<String, Object> gameState = new LinkedHashMap<>();
                gameState.put("period", currentPeriod);
                gameState.put("timeLeft", timeLeft);
                gameState.put("status", currentStatus);
                gameState.put("history", getHistoryTop25());

                return Map.of("user", userMap, "gameState", gameState);
            });
        } finally {
            stateLock.unlock();
        }
    }

    public Map<String, Object> getAdminInitPayload() {
        stateLock.lock();
        try {
            Map<String, Object> analytics = buildAdminAnalyticsSnapshot();
            List<Map<String, Object>> history = getHistoryTop25();
            return Map.of("analytics", analytics, "history", history);
        } finally {
            stateLock.unlock();
        }
    }

    public Map<String, Object> switchMode(String email, String mode) {
        GameEnums.ActiveMode target = "REAL".equalsIgnoreCase(mode)
                ? GameEnums.ActiveMode.REAL : GameEnums.ActiveMode.DEMO;

        Map<String, Object> sanitized;
        stateLock.lock();
        try {
            sanitized = txTemplate.execute(status -> {
                UserEntity user = userRepo.findByEmail(email).orElse(null);
                if (user == null) return null;
                user.setActiveMode(target);
                userRepo.save(user);
                return buildSanitizedUser(user);
            });
        } finally {
            stateLock.unlock();
        }

        if (sanitized != null) {
            wsHub.emitToUser(email, "user_updated", sanitized);
        }
        return Map.of("success", true, "mode", target.name());
    }

    public Map<String, Object> placeBet(String email, String typeStr, String choiceStr, Integer amount) {
        if (amount == null || amount < 10) {
            return Map.of("success", false, "message", "Minimum bet amount is ₹10.");
        }

        GameEnums.BetType betType;
        try {
            betType = GameEnums.BetType.valueOf(String.valueOf(typeStr).trim().toUpperCase());
        } catch (Exception ex) {
            return Map.of("success", false, "message", "Invalid bet type.");
        }
        String choice = choiceStr == null ? "" : choiceStr.trim().toUpperCase();
        if (choice.isEmpty()) {
            return Map.of("success", false, "message", "Bet choice is required.");
        }

        Map<String, Object> betMap;
        Map<String, Object> userMap;
        List<Map<String, Object>> activeUserBets;
        Map<String, Object> adminSnapshot;

        stateLock.lock();
        try {
            if ("LOCKED".equals(currentStatus) || timeLeft <= 5) {
                return Map.of("success", false, "message", "Round is locked! Please wait for the next period.");
            }

            UserEntity user = userRepo.findByEmail(email).orElse(null);
            if (user == null) {
                return Map.of("success", false, "message", "User account not found.");
            }

            boolean isReal = user.getActiveMode() == GameEnums.ActiveMode.REAL;
            BigDecimal currentBal = isReal ? user.getRealBalance() : user.getDemoBalance();
            BigDecimal amtBd = BigDecimal.valueOf(amount);

            if (currentBal.compareTo(amtBd) < 0) {
                String msg = isReal
                        ? "Insufficient Real balance! Please Deposit to play with Real money."
                        : "Insufficient Demo balance! Reload free demo cash.";
                return Map.of("success", false, "message", msg);
            }

            BetEntity newBet = new BetEntity();
            newBet.setId("BET_" + System.currentTimeMillis() + Integer.toHexString(RANDOM.nextInt(0xFFFF)));
            newBet.setEmail(email);
            newBet.setPeriod(currentPeriod);
            newBet.setBetType(betType);
            newBet.setChoice(choice);
            newBet.setAmount(amtBd);
            newBet.setMode(user.getActiveMode());
            newBet.setStatus(GameEnums.BetStatus.ACTIVE);
            newBet.setCreatedAt(Instant.now());

            Map<String, Object> txOut = txTemplate.execute(status -> {
                UserEntity u = userRepo.findByEmail(email).orElseThrow();
                if (isReal) {
                    u.setRealBalance(u.getRealBalance().subtract(amtBd));
                } else {
                    u.setDemoBalance(u.getDemoBalance().subtract(amtBd));
                }
                userRepo.save(u);
                betRepo.save(newBet);
                return buildSanitizedUser(u);
            });

            currentBets.add(newBet);
            userMap = txOut;
            betMap = payloadBuilder.toActiveBetMap(newBet, user.getUsername());
            activeUserBets = currentBets.stream()
                    .filter(b -> email.equals(b.getEmail()))
                    .map(b -> payloadBuilder.toActiveBetMap(b, user.getUsername()))
                    .toList();
            adminSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }

        wsHub.emitToUser(email, "active_bets_updated", activeUserBets);
        wsHub.emitToUser(email, "user_updated", userMap);
        wsHub.emitToAdmin("admin_live_update", adminSnapshot);

        return Map.of(
                "success", true,
                "message", "Bet placed on " + choice + " for ₹" + amount + "!",
                "bet", betMap
        );
    }

    public Map<String, Object> cancelBet(String email, String betId) {
        Map<String, Object> userMap;
        List<Map<String, Object>> activeUserBets;
        Map<String, Object> adminSnapshot;
        String cancelledChoice;
        long cancelledAmt;

        stateLock.lock();
        try {
            if ("LOCKED".equals(currentStatus) || timeLeft <= 5) {
                return Map.of("success", false, "message", "Cannot cancel! The round is locked in the final 5 seconds.");
            }

            int idx = -1;
            for (int i = 0; i < currentBets.size(); i++) {
                BetEntity b = currentBets.get(i);
                if (b.getId().equals(betId) && email.equals(b.getEmail())) {
                    idx = i;
                    break;
                }
            }
            if (idx == -1) {
                return Map.of("success", false, "message", "Bet not found or already settled.");
            }

            BetEntity target = currentBets.get(idx);
            cancelledChoice = target.getChoice();
            cancelledAmt = target.getAmount().longValue();

            Map<String, Object> txOut = txTemplate.execute(status -> {
                UserEntity u = userRepo.findByEmail(email).orElseThrow();
                if (target.getMode() == GameEnums.ActiveMode.REAL) {
                    u.setRealBalance(u.getRealBalance().add(target.getAmount()));
                } else {
                    u.setDemoBalance(u.getDemoBalance().add(target.getAmount()));
                }
                userRepo.save(u);

                target.setStatus(GameEnums.BetStatus.CANCELLED);
                target.setPayout(BigDecimal.ZERO);
                target.setNetProfit(BigDecimal.ZERO);
                betRepo.save(target);
                return buildSanitizedUser(u);
            });

            currentBets.remove(idx);
            userMap = txOut;
            String uname = String.valueOf(userMap.get("username"));
            activeUserBets = currentBets.stream()
                    .filter(b -> email.equals(b.getEmail()))
                    .map(b -> payloadBuilder.toActiveBetMap(b, uname))
                    .toList();
            adminSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }

        wsHub.emitToUser(email, "active_bets_updated", activeUserBets);
        wsHub.emitToUser(email, "user_updated", userMap);
        wsHub.emitToAdmin("admin_live_update", adminSnapshot);

        return Map.of(
                "success", true,
                "message", "Cancelled bet on " + cancelledChoice + ". ₹" + cancelledAmt + " refunded!"
        );
    }

    public Map<String, Object> addDemoBalance(String email, Integer amount) {
        int addAmt = (amount == null || amount <= 0) ? 500 : amount;
        Map<String, Object> userMap;
        Map<String, Object> adminSnapshot;

        stateLock.lock();
        try {
            userMap = txTemplate.execute(status -> {
                UserEntity u = userRepo.findByEmail(email).orElseThrow();
                u.setDemoBalance(u.getDemoBalance().add(BigDecimal.valueOf(addAmt)));
                userRepo.save(u);
                return buildSanitizedUser(u);
            });
            adminSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }

        wsHub.emitToUser(email, "user_updated", userMap);
        wsHub.emitToAdmin("admin_live_update", adminSnapshot);
        return Map.of("success", true, "message", "Added ₹" + addAmt + " demo cash!");
    }

    public Map<String, Object> submitDeposit(String email, Integer amount, String utr, String method) {
        if (amount == null || amount < 100) {
            return Map.of("success", false, "message", "Minimum deposit is ₹100.");
        }
        String cleanUtr = utr == null ? "" : utr.trim();
        if (cleanUtr.length() < 8) {
            return Map.of("success", false, "message", "Please enter a valid 12-digit UTR / Ref Number.");
        }
        String cleanMethod = (method == null || method.isBlank()) ? "UPI" : method.trim();

        Map<String, Object> userMap;
        Map<String, Object> adminSnapshot;

        stateLock.lock();
        try {
            userMap = txTemplate.execute(status -> {
                UserEntity u = userRepo.findByEmail(email).orElseThrow();
                BigDecimal depBd = BigDecimal.valueOf(amount);
                u.setRealBalance(u.getRealBalance().add(depBd));
                u.setTotalRecharged(u.getTotalRecharged().add(depBd));
                u.setLastRechargeAt(Instant.now());
                u.setLastRechargeAmt(depBd);
                userRepo.save(u);

                PlatformSettingsEntity s = loadOrInitSettings();
                s.setTotalRecharges(s.getTotalRecharges().add(depBd));
                settingsRepo.save(s);

                TransactionEntity tx = new TransactionEntity();
                tx.setId("DEP_" + System.currentTimeMillis() + Integer.toHexString(RANDOM.nextInt(0xFFF)));
                tx.setEmail(email);
                tx.setTxType(GameEnums.TxType.DEPOSIT);
                tx.setMethod(cleanMethod);
                tx.setAccountOrUtr(cleanUtr);
                tx.setAmount(depBd);
                tx.setStatus("SUCCESS");
                tx.setCreatedAt(Instant.now());
                txRepo.save(tx);

                return buildSanitizedUser(u);
            });
            adminSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }

        wsHub.emitToUser(email, "user_updated", userMap);
        wsHub.emitToAdmin("admin_live_update", adminSnapshot);
        return Map.of("success", true, "message", "Deposit of ₹" + amount + " confirmed & credited to Real Balance!");
    }

    public Map<String, Object> submitWithdrawal(String email, Integer amount, String upiOrBank) {
        if (amount == null || amount < 200) {
            return Map.of("success", false, "message", "Minimum withdrawal is ₹200.");
        }
        String cleanAcct = upiOrBank == null ? "" : upiOrBank.trim();
        if (cleanAcct.length() < 4) {
            return Map.of("success", false, "message", "Please enter a valid UPI ID or Bank details.");
        }

        Map<String, Object> userMap;
        Map<String, Object> adminSnapshot;

        stateLock.lock();
        try {
            UserEntity check = userRepo.findByEmail(email).orElse(null);
            if (check == null) {
                return Map.of("success", false, "message", "User not found.");
            }
            BigDecimal wthBd = BigDecimal.valueOf(amount);
            if (check.getRealBalance().compareTo(wthBd) < 0) {
                return Map.of("success", false, "message", "Insufficient Real Balance to withdraw!");
            }

            userMap = txTemplate.execute(status -> {
                UserEntity u = userRepo.findByEmail(email).orElseThrow();
                u.setRealBalance(u.getRealBalance().subtract(wthBd));
                userRepo.save(u);

                String reqId = "WTH_" + System.currentTimeMillis() + Integer.toHexString(RANDOM.nextInt(0xFFFF));

                WithdrawRequestEntity wr = new WithdrawRequestEntity();
                wr.setId(reqId);
                wr.setEmail(u.getEmail());
                wr.setUserId(u.getUserId());
                wr.setUsername(u.getUsername());
                wr.setAmount(wthBd);
                wr.setAccountDetails(cleanAcct);
                wr.setStatus(GameEnums.WithdrawStatus.PENDING);
                wr.setRequestedAt(Instant.now());
                withdrawRepo.save(wr);

                TransactionEntity tx = new TransactionEntity();
                tx.setId(reqId);
                tx.setEmail(email);
                tx.setTxType(GameEnums.TxType.WITHDRAWAL);
                tx.setAccountOrUtr(cleanAcct);
                tx.setAmount(wthBd);
                tx.setStatus("PENDING (Admin Review)");
                tx.setCreatedAt(Instant.now());
                txRepo.save(tx);

                return buildSanitizedUser(u);
            });
            adminSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }

        wsHub.emitToUser(email, "user_updated", userMap);
        wsHub.emitToAdmin("admin_live_update", adminSnapshot);
        return Map.of("success", true, "message", "Withdrawal request for ₹" + amount + " sent to Admin! Status: Pending.");
    }

    public Map<String, Object> setOutcomeMode(String modeStr, Integer forcedNum) {
        Map<String, Object> adminSnapshot;
        String finalMode;
        Integer finalForced;

        stateLock.lock();
        try {
            finalMode = txTemplate.execute(status -> {
                PlatformSettingsEntity s = loadOrInitSettings();
                if ("SMART_PROFIT".equals(modeStr) || "FAIR_RANDOM".equals(modeStr)) {
                    s.setOutcomeMode(GameEnums.OutcomeMode.valueOf(modeStr));
                    forcedNextResult = null;
                } else if ("MANUAL".equals(modeStr) && forcedNum != null && forcedNum >= 0 && forcedNum <= 9) {
                    s.setOutcomeMode(GameEnums.OutcomeMode.MANUAL);
                    forcedNextResult = forcedNum;
                }
                settingsRepo.save(s);
                return s.getOutcomeMode().name();
            });
            finalForced = forcedNextResult;
            adminSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }

        wsHub.emitToAdmin("admin_live_update", adminSnapshot);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("mode", finalMode);
        resp.put("forcedNextResult", finalForced);
        return resp;
    }

    public void updateSmtpConfig(String adminEmail, String host, Integer port, String authUser, String authPass, String fromName) {
        Map<String, Object> adminSnapshot;
        stateLock.lock();
        try {
            txTemplate.executeWithoutResult(status -> {
                PlatformSettingsEntity s = loadOrInitSettings();
                if (adminEmail != null && adminEmail.contains("@")) {
                    s.setAdminEmail(adminEmail.trim().toLowerCase());
                }
                s.setSmtpHost(host == null || host.isBlank() ? "smtp.gmail.com" : host.trim());
                s.setSmtpPort(port != null && port > 0 ? port : 587);
                s.setSmtpUser(authUser == null ? "" : authUser.trim());
                if (authPass != null && !authPass.isBlank() && !"********".equals(authPass)) {
                    s.setSmtpPass(authPass.replaceAll("\\s+", "").trim());
                }
                s.setSmtpFromName(fromName == null || fromName.isBlank() ? "GB_Colors" : fromName.trim());
                settingsRepo.save(s);
            });
            adminSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }
        wsHub.emitToAdmin("admin_live_update", adminSnapshot);
    }

    public void changeAdminPassword(String newPassword) {
        stateLock.lock();
        try {
            txTemplate.executeWithoutResult(status -> {
                PlatformSettingsEntity s = loadOrInitSettings();
                s.setAdminPasswordHash(passwordService.hashPassword(newPassword));
                settingsRepo.save(s);
            });
        } finally {
            stateLock.unlock();
        }
    }

    public Map<String, Object> processWithdrawAction(String requestId, String action) {
        Map<String, Object> updatedUserMap = null;
        String targetEmail = null;
        Map<String, Object> adminSnapshot;

        stateLock.lock();
        try {
            WithdrawRequestEntity existing = withdrawRepo.findById(requestId).orElse(null);
            if (existing == null) {
                return Map.of("success", false, "message", "Withdrawal request not found.");
            }
            if (existing.getStatus() != GameEnums.WithdrawStatus.PENDING) {
                return Map.of("success", false, "message", "Request already processed.");
            }
            targetEmail = existing.getEmail();

            final String fEmail = targetEmail;
            updatedUserMap = txTemplate.execute(status -> {
                WithdrawRequestEntity wr = withdrawRepo.findById(requestId).orElseThrow();
                UserEntity u = userRepo.findByEmail(fEmail).orElse(null);
                TransactionEntity tx = txRepo.findById(wr.getId()).orElse(null);

                if ("PAID".equalsIgnoreCase(action)) {
                    wr.setStatus(GameEnums.WithdrawStatus.PAID);
                    wr.setProcessedAt(Instant.now());
                    PlatformSettingsEntity s = loadOrInitSettings();
                    s.setTotalWithdrawalsPaid(s.getTotalWithdrawalsPaid().add(wr.getAmount()));
                    settingsRepo.save(s);

                    if (u != null) {
                        u.setTotalWithdrawn(u.getTotalWithdrawn().add(wr.getAmount()));
                        u.setLastWithdrawAt(Instant.now());
                        u.setLastWithdrawAmt(wr.getAmount());
                        userRepo.save(u);
                    }
                    if (tx != null) {
                        tx.setStatus("PAID");
                        txRepo.save(tx);
                    }
                } else if ("REJECTED".equalsIgnoreCase(action)) {
                    wr.setStatus(GameEnums.WithdrawStatus.REJECTED);
                    wr.setProcessedAt(Instant.now());
                    if (u != null) {
                        u.setRealBalance(u.getRealBalance().add(wr.getAmount()));
                        userRepo.save(u);
                    }
                    if (tx != null) {
                        tx.setStatus("REJECTED (Refunded)");
                        txRepo.save(tx);
                    }
                }
                withdrawRepo.save(wr);
                return u != null ? buildSanitizedUser(u) : null;
            });

            adminSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }

        if (targetEmail != null && updatedUserMap != null) {
            wsHub.emitToUser(targetEmail, "user_updated", updatedUserMap);
        }
        wsHub.emitToAdmin("admin_live_update", adminSnapshot);
        return Map.of("success", true, "message", "Withdrawal request marked as " + action + "!");
    }

    public Map<String, Object> adjustUserBalance(String email, int delta) {
        Map<String, Object> updatedUserMap;
        Map<String, Object> adminSnapshot;

        stateLock.lock();
        try {
            if (!userRepo.existsByEmail(email)) {
                return Map.of("success", false, "message", "Invalid user or amount.");
            }
            updatedUserMap = txTemplate.execute(status -> {
                UserEntity u = userRepo.findByEmail(email).orElseThrow();
                BigDecimal nextBal = u.getRealBalance().add(BigDecimal.valueOf(delta));
                if (nextBal.compareTo(BigDecimal.ZERO) < 0) nextBal = BigDecimal.ZERO;
                u.setRealBalance(nextBal);

                if (delta > 0) {
                    BigDecimal deltaBd = BigDecimal.valueOf(delta);
                    u.setTotalRecharged(u.getTotalRecharged().add(deltaBd));
                    u.setLastRechargeAt(Instant.now());
                    u.setLastRechargeAmt(deltaBd);
                    PlatformSettingsEntity s = loadOrInitSettings();
                    s.setTotalRecharges(s.getTotalRecharges().add(deltaBd));
                    settingsRepo.save(s);
                }
                userRepo.save(u);
                return buildSanitizedUser(u);
            });
            adminSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }

        wsHub.emitToUser(email, "user_updated", updatedUserMap);
        wsHub.emitToAdmin("admin_live_update", adminSnapshot);
        return Map.of("success", true, "message", "Adjusted " + email + " real balance by ₹" + delta);
    }

    public void broadcastAdminUpdate() {
        Map<String, Object> snapshot;
        stateLock.lock();
        try {
            snapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }
        wsHub.emitToAdmin("admin_live_update", snapshot);
    }

    private PlatformSettingsEntity loadOrInitSettings() {
        return settingsRepo.findById(1).orElseGet(() -> {
            PlatformSettingsEntity s = new PlatformSettingsEntity();
            s.setId(1);
            s.setAdminEmail(envAdminEmail.trim().toLowerCase());
            s.setAdminPasswordHash(passwordService.hashPassword(envAdminPass));
            s.setOutcomeMode(GameEnums.OutcomeMode.SMART_PROFIT);
            s.setRoundCounter(26);
            s.setSmtpHost(envSmtpHost);
            s.setSmtpPort(envSmtpPort);
            s.setSmtpUser(envSmtpUser);
            s.setSmtpPass(envSmtpPass);
            s.setSmtpFromName(envSmtpFromName);
            return settingsRepo.save(s);
        });
    }

    private Map<String, Object> buildSanitizedUser(UserEntity user) {
        List<BetEntity> bets = betRepo.findTop60ByEmailAndStatusNotOrderByCreatedAtDesc(
                user.getEmail(), GameEnums.BetStatus.ACTIVE
        );
        List<TransactionEntity> txs = txRepo.findTop100ByEmailOrderByCreatedAtDesc(user.getEmail());
        return payloadBuilder.sanitizeUser(user, bets, txs);
    }

    private List<Map<String, Object>> getHistoryTop25() {
        List<GameRoundEntity> all = roundRepo.findTop50ByOrderByIdDesc();
        int limit = Math.min(25, all.size());
        return all.subList(0, limit).stream().map(payloadBuilder::toRoundMap).toList();
    }

    private Map<String, Object> buildAdminAnalyticsSnapshot() {
        PlatformSettingsEntity settings = loadOrInitSettings();
        List<UserEntity> users = userRepo.findAll();
        List<WithdrawRequestEntity> withdrawals = withdrawRepo.findAllByOrderByRequestedAtDesc();
        return payloadBuilder.buildAdminAnalyticsPayload(
                currentPeriod,
                timeLeft,
                currentStatus,
                forcedNextResult,
                settings,
                new ArrayList<>(currentBets),
                users,
                withdrawals
        );
    }
}
