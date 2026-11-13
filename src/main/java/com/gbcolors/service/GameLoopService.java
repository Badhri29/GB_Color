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
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Coordinates the configurable game countdown loop, round settlement, bet lifecycle,
 * wallet operations, user blocking, notifications, live ticker, and Admin analytics.
 */
@Service
public class GameLoopService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_TICKER_HISTORY = 40;
    private static final Pattern IFSC_PATTERN = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");

    private final ReentrantLock stateLock = new ReentrantLock();
    private final TransactionTemplate txTemplate;
    private final UserRepository userRepo;
    private final GameRoundRepository roundRepo;
    private final BetRepository betRepo;
    private final TransactionRepository txRepo;
    private final WithdrawRequestRepository withdrawRepo;
    private final DepositRequestRepository depositRepo;
    private final NotificationRepository notificationRepo;
    private final AdminMessageLogRepository adminMsgLogRepo;
    private final SessionRepository sessionRepo;
    private final PlatformSettingsRepository settingsRepo;
    private final GameRuleEngine ruleEngine;
    private final GamePayloadBuilder payloadBuilder;
    private final PasswordService passwordService;
    private final WebSocketHub wsHub;

    private final Deque<Map<String, Object>> liveWinTickerBuffer = new ConcurrentLinkedDeque<>();

    @Value("${server.port:7070}")
    private int serverPort;

    @Value("${gbcolors.admin-email:gbcolor@admin.com}")
    private String envAdminEmail;

    @Value("${gbcolors.admin-default-password:Admin@123}")
    private String envAdminPass;

    @Value("${gbcolors.smtp.host:smtp.gmail.com}")
    private String envSmtpHost;

    @Value("${gbcolors.smtp.port:587}")
    private int envSmtpPort;

    @Value("${gbcolors.smtp.user:gbcolor@admin.com}")
    private String envSmtpUser;

    @Value("${gbcolors.smtp.pass:}")
    private String envSmtpPass;

    @Value("${gbcolors.smtp.from-name:GB_Colors}")
    private String envSmtpFromName;

    @Value("${gbcolors.upi-id:gbpayment0@nyes}")
    private String envUpiId;

    @Value("${gbcolors.upi-payee-name:GB Colors}")
    private String envUpiPayeeName;

    @Value("${gbcolors.razorpay.key-id:}")
    private String envRazorpayKeyId;

    @Value("${gbcolors.razorpay.key-secret:}")
    private String envRazorpayKeySecret;

    @Value("${gbcolors.seed-sample-users:false}")
    private boolean seedSampleUsers;

    private int timeLeft = 30;
    private String currentStatus = "BETTING";
    private String currentPeriod = "";
    private Integer forcedNextResult = null;
    private Integer lockedWinningNumber = null;
    private final List<BetEntity> currentBets = new ArrayList<>();

    public GameLoopService(
            TransactionTemplate txTemplate,
            UserRepository userRepo,
            GameRoundRepository roundRepo,
            BetRepository betRepo,
            TransactionRepository txRepo,
            WithdrawRequestRepository withdrawRepo,
            DepositRequestRepository depositRepo,
            NotificationRepository notificationRepo,
            AdminMessageLogRepository adminMsgLogRepo,
            SessionRepository sessionRepo,
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
        this.depositRepo = depositRepo;
        this.notificationRepo = notificationRepo;
        this.adminMsgLogRepo = adminMsgLogRepo;
        this.sessionRepo = sessionRepo;
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
                String cleanAdminEmail = (envAdminEmail == null || envAdminEmail.isBlank() || envAdminEmail.toLowerCase().contains("gmail"))
                        ? "gbcolor@admin.com"
                        : envAdminEmail.trim().toLowerCase();
                if (s == null) {
                    s = new PlatformSettingsEntity();
                    s.setId(1);
                    s.setAdminEmail(cleanAdminEmail);
                    s.setAdminPasswordHash(passwordService.hashPassword(envAdminPass));
                    s.setOutcomeMode(GameEnums.OutcomeMode.SMART_PROFIT);
                    s.setRoundCounter(26);
                    s.setCycleDurationSeconds(30);
                    s.setBetCloseSeconds(5);
                    s.setAdminMinProfitPercent(15);
                    s.setMinDepositAmount(10);
                    s.setMinWithdrawAmount(10);
                } else {
                    if (s.getAdminEmail() == null || s.getAdminEmail().isBlank() || s.getAdminEmail().toLowerCase().contains("gmail") || "gbcolor.admin@gmail.com".equalsIgnoreCase(s.getAdminEmail().trim())) {
                        s.setAdminEmail(cleanAdminEmail);
                    }
                    if (s.getAdminPasswordHash() == null || !s.getAdminPasswordHash().startsWith("$2")) {
                        s.setAdminPasswordHash(passwordService.hashPassword(envAdminPass));
                    }
                }
                if (s.getSmtpHost() == null || s.getSmtpHost().isBlank()) s.setSmtpHost(envSmtpHost);
                if (s.getSmtpPort() <= 0) s.setSmtpPort(envSmtpPort);
                if (envSmtpUser != null && !envSmtpUser.isBlank()) s.setSmtpUser(envSmtpUser.trim());
                if (envSmtpPass != null && !envSmtpPass.isBlank()) s.setSmtpPass(envSmtpPass.replaceAll("\\s+", "").trim());
                if (envSmtpFromName != null && !envSmtpFromName.isBlank()) s.setSmtpFromName(envSmtpFromName.trim());

                if (s.getUpiId() == null || s.getUpiId().isBlank() || "gbcolors.pay@upi".equalsIgnoreCase(s.getUpiId().trim()) || s.getUpiId().toLowerCase().contains("badhri")) {
                    s.setUpiId(envUpiId);
                }
                if (s.getUpiPayeeName() == null || s.getUpiPayeeName().isBlank() || s.getUpiPayeeName().toLowerCase().contains("badhri")) {
                    s.setUpiPayeeName(envUpiPayeeName);
                }
                if (s.getMinDepositAmount() > 10) {
                    s.setMinDepositAmount(10);
                }
                if (s.getMinWithdrawAmount() > 10) {
                    s.setMinWithdrawAmount(10);
                }
                if ((s.getRazorpayKeyId() == null || s.getRazorpayKeyId().isBlank()) && envRazorpayKeyId != null && !envRazorpayKeyId.isBlank()) {
                    s.setRazorpayKeyId(envRazorpayKeyId.trim());
                }
                if ((s.getRazorpayKeySecret() == null || s.getRazorpayKeySecret().isBlank()) && envRazorpayKeySecret != null && !envRazorpayKeySecret.isBlank()) {
                    s.setRazorpayKeySecret(envRazorpayKeySecret.trim());
                }

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
                ensureAdminPlayWallet(s.getAdminEmail());
                cleanupUsersKeepOnlyBadhri();
                return s;
            });

            timeLeft = settings.getCycleDurationSeconds();
            currentPeriod = ruleEngine.generatePeriodString(settings.getRoundCounter());
            List<BetEntity> activeFromDb = betRepo.findByStatus(GameEnums.BetStatus.ACTIVE);
            currentBets.clear();
            for (BetEntity b : activeFromDb) {
                if (currentPeriod.equals(b.getPeriod())) {
                    currentBets.add(b);
                }
            }

            // Seed real win ticker from recent real winning bets
            List<BetEntity> recentWins = betRepo.findTop30ByStatusOrderByCreatedAtDesc(GameEnums.BetStatus.WON);
            for (BetEntity b : recentWins) {
                if (b.getMode() == GameEnums.ActiveMode.REAL && b.getPayout() != null && b.getPayout().longValue() > 0) {
                    pushTickerEvent(buildRealWinTickerEvent(b));
                }
            }

            System.out.println("====================================================");
            System.out.println("🚀 GB_Colors Java Server booted cleanly!");
            System.out.println("🔗 Local URL: http://localhost:" + serverPort);
            System.out.println("📧 OTP Sender: " + settings.getSmtpUser());
            System.out.println("🔐 Admin Email: " + settings.getAdminEmail());
            System.out.println("⏱️  Cycle: " + settings.getCycleDurationSeconds() + "s | Close: " + settings.getBetCloseSeconds() + "s | Min Margin: " + settings.getAdminMinProfitPercent() + "%");
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
            PlatformSettingsEntity settings = loadOrInitSettings();
            int cycleSecs = settings.getCycleDurationSeconds();
            int closeSecs = getEffectiveCloseSeconds(settings);

            timeLeft--;
            if (timeLeft <= closeSecs && timeLeft > 0) {
                if (!"LOCKED".equals(currentStatus) || lockedWinningNumber == null) {
                    currentStatus = "LOCKED";
                    lockedWinningNumber = computeUpcomingWinningNumber(settings);
                    logLockedPreview(buildAdminAnalyticsSnapshot());
                }
            } else if (timeLeft <= 0) {
                SettlementResult settlement = settleRoundInsideTx();
                roundResultBroadcast = settlement.roundResultBroadcast();
                settledUserPayloads = settlement.settledUserPayloads();

                lockedWinningNumber = null;
                forcedNextResult = null;
                currentStatus = "BETTING";
                // Reload settings in case cycle duration was updated
                settings = loadOrInitSettings();
                cycleSecs = settings.getCycleDurationSeconds();
                closeSecs = getEffectiveCloseSeconds(settings);
                timeLeft = cycleSecs;
                currentBets.clear();
            }

            tickPayload = new LinkedHashMap<>();
            tickPayload.put("period", currentPeriod);
            tickPayload.put("timeLeft", timeLeft);
            tickPayload.put("status", currentStatus);
            tickPayload.put("activeBetsCount", currentBets.size());
            tickPayload.put("cycleDurationSeconds", cycleSecs);
            tickPayload.put("betCloseSeconds", closeSecs);

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

    private int getEffectiveCloseSeconds(PlatformSettingsEntity s) {
        int cycle = s.getCycleDurationSeconds();
        int close = s.getBetCloseSeconds();
        return Math.max(2, Math.min(close, Math.max(2, cycle - 1)));
    }

    private int computeUpcomingWinningNumber(PlatformSettingsEntity settings) {
        if (forcedNextResult != null && forcedNextResult >= 0 && forcedNextResult <= 9) {
            return forcedNextResult;
        }
        if (settings.getOutcomeMode() == GameEnums.OutcomeMode.SMART_PROFIT && !currentBets.isEmpty()) {
            GameRuleEngine.ProjectionSummary summary = ruleEngine.computeRoundProjections(
                    currentBets,
                    settings.getAdminMinProfitPercent()
            );
            List<GameRuleEngine.NumberProjection> best = summary.bestCandidates();
            return best.get(RANDOM.nextInt(best.size())).number();
        }
        return RANDOM.nextInt(10);
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
        List<Map<String, Object>> newTickerEvents = new ArrayList<>();

        txTemplate.executeWithoutResult(status -> {
            PlatformSettingsEntity settings = loadOrInitSettings();
            int winningNumber;

            if (forcedNextResult != null && forcedNextResult >= 0 && forcedNextResult <= 9) {
                winningNumber = forcedNextResult;
                forcedNextResult = null;
                if (settings.getOutcomeMode() == GameEnums.OutcomeMode.MANUAL) {
                    settings.setOutcomeMode(GameEnums.OutcomeMode.SMART_PROFIT);
                }
            } else if (lockedWinningNumber != null && lockedWinningNumber >= 0 && lockedWinningNumber <= 9) {
                winningNumber = lockedWinningNumber;
            } else {
                winningNumber = computeUpcomingWinningNumber(settings);
            }

            while (roundRepo.existsByPeriod(currentPeriod)) {
                settings.setRoundCounter(settings.getRoundCounter() + 1);
                currentPeriod = ruleEngine.generatePeriodString(settings.getRoundCounter());
            }

            BigDecimal roundRealCollected = BigDecimal.ZERO;
            BigDecimal roundRealPayout = BigDecimal.ZERO;
            int winnersCount = 0;

            for (BetEntity bet : currentBets) {
                GameRuleEngine.BetEvalResult eval = ruleEngine.evaluateBetOutcome(bet, winningNumber);
                BigDecimal amtBd = bet.getAmount();
                BigDecimal payoutBd = BigDecimal.valueOf(eval.payout());
                BigDecimal netProfitBd = payoutBd.subtract(amtBd);

                if (bet.getMode() == GameEnums.ActiveMode.REAL) {
                    roundRealCollected = roundRealCollected.add(amtBd);
                    roundRealPayout = roundRealPayout.add(payoutBd);
                    settings.setTotalBetsCollectedReal(settings.getTotalBetsCollectedReal().add(amtBd));
                    settings.setTotalPayoutsPaidReal(settings.getTotalPayoutsPaidReal().add(payoutBd));
                    settings.setTotalHouseProfit(settings.getTotalBetsCollectedReal().subtract(settings.getTotalPayoutsPaidReal()));
                } else {
                    settings.setTotalDemoCollected(settings.getTotalDemoCollected().add(amtBd));
                    settings.setTotalDemoPayouts(settings.getTotalDemoPayouts().add(payoutBd));
                }

                bet.setStatus(eval.win() ? GameEnums.BetStatus.WON : (payoutBd.compareTo(BigDecimal.ZERO) > 0 ? GameEnums.BetStatus.WON : GameEnums.BetStatus.LOST));
                bet.setPayout(payoutBd);
                bet.setNetProfit(netProfitBd);
                bet.setResultNumber(winningNumber);
                betRepo.save(bet);

                if (eval.win() && bet.getMode() == GameEnums.ActiveMode.REAL && payoutBd.longValue() > 0) {
                    Map<String, Object> tickerEv = buildRealWinTickerEvent(bet);
                    pushTickerEvent(tickerEv);
                    newTickerEvents.add(tickerEv);
                }

                UserEntity user = userRepo.findByEmail(bet.getEmail()).orElse(null);
                if (user != null) {
                    if (payoutBd.compareTo(BigDecimal.ZERO) > 0) {
                        if (bet.getMode() == GameEnums.ActiveMode.REAL) {
                            user.setRealBalance(user.getRealBalance().add(payoutBd));
                            if (eval.win()) {
                                winnersCount++;
                                user.setTotalWon(user.getTotalWon().add(netProfitBd));
                            } else {
                                user.setTotalLost(user.getTotalLost().add(amtBd.subtract(payoutBd)));
                            }
                        } else {
                            if (eval.win()) winnersCount++;
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

            GameRuleEngine.OutcomeProps props = ruleEngine.getOutcomeProps(winningNumber);
            GameRoundEntity roundEntity = new GameRoundEntity();
            roundEntity.setPeriod(currentPeriod);
            roundEntity.setWinningNumber(winningNumber);
            roundEntity.setColors(String.join(",", props.colors()));
            roundEntity.setSize(props.size());
            roundEntity.setTotalCollected(roundRealCollected);
            roundEntity.setTotalPayout(roundRealPayout);
            roundEntity.setHouseProfit(roundRealCollected.subtract(roundRealPayout));
            roundEntity.setCreatedAt(Instant.now());
            savedRoundRef.set(roundRepo.save(roundEntity));

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
        broadcast.put("newTickerEvents", newTickerEvents);

        return new SettlementResult(broadcast, settledUserPayloads);
    }

    public PlatformSettingsEntity getPlatformSettings() {
        return txTemplate.execute(status -> loadOrInitSettings());
    }

    public Map<String, Object> getSanitizedUserPayload(String email) {
        return txTemplate.execute(status -> userRepo.findByEmail(email)
                .map(this::buildSanitizedUser)
                .orElse(null));
    }

    public Map<String, Object> getInitialStateForUser(String email) {
        stateLock.lock();
        try {
            return txTemplate.execute(status -> {
                UserEntity user = userRepo.findByEmail(email).orElse(null);
                if (user == null) return null;

                PlatformSettingsEntity settings = loadOrInitSettings();
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
                gameState.put("cycleDurationSeconds", settings.getCycleDurationSeconds());
                gameState.put("betCloseSeconds", getEffectiveCloseSeconds(settings));
                gameState.put("history", getHistoryTop25());
                gameState.put("tickerEvents", getTickerEventsSnapshot());

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
        if (betType == GameEnums.BetType.SAFE_COLOR) {
            if (!"GREEN".equals(choice) && !"RED".equals(choice)) {
                return Map.of("success", false, "message", "Game 2 Safe Protect mode only accepts GREEN or RED.");
            }
        }

        Map<String, Object> betMap;
        Map<String, Object> userMap;
        List<Map<String, Object>> activeUserBets;
        Map<String, Object> adminSnapshot;

        stateLock.lock();
        try {
            PlatformSettingsEntity settings = loadOrInitSettings();
            int closeSecs = getEffectiveCloseSeconds(settings);
            if ("LOCKED".equals(currentStatus) || timeLeft <= closeSecs) {
                return Map.of("success", false, "message", "Round is locked! Please wait for the next period.");
            }

            UserEntity user = userRepo.findByEmail(email).orElse(null);
            if (user == null) {
                return Map.of("success", false, "message", "User account not found.");
            }
            String blockErr = getBlockMessageIfActive(user);
            if (blockErr != null) {
                return Map.of("success", false, "message", blockErr);
            }

            boolean isReal = user.getActiveMode() == GameEnums.ActiveMode.REAL;
            BigDecimal currentBal = isReal ? user.getRealBalance() : user.getDemoBalance();
            BigDecimal amtBd = BigDecimal.valueOf(amount);

            if (currentBal.compareTo(amtBd) < 0) {
                String msg = isReal
                        ? "Insufficient Real balance! Please Recharge on the Payment page."
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
            PlatformSettingsEntity settings = loadOrInitSettings();
            int closeSecs = getEffectiveCloseSeconds(settings);
            if ("LOCKED".equals(currentStatus) || timeLeft <= closeSecs) {
                return Map.of("success", false, "message", "Cannot cancel! The round is locked in the final " + closeSecs + " seconds.");
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

    /**
     * Submits a manual UPI UTR deposit request into the Admin Verification Queue.
     */
    public Map<String, Object> submitDeposit(String email, Integer amount, String utr, String method) {
        return submitDeposit(email, amount, utr, method, null);
    }

    public Map<String, Object> submitDeposit(String email, Integer amount, String utr, String method, String proofImage) {
        String amtErr = validateDepositAmount(amount);
        if (amtErr != null) {
            return Map.of("success", false, "message", amtErr);
        }
        String cleanUtr = utr == null ? "" : utr.trim();
        if (cleanUtr.length() < 8) {
            return Map.of("success", false, "message", "Please enter a valid 12-digit UTR / Ref Number.");
        }
        String cleanMethod = (method == null || method.isBlank()) ? "UPI" : method.trim().toUpperCase();

        Map<String, Object> userMap;
        Map<String, Object> adminSnapshot;
        String depositId;

        stateLock.lock();
        try {
            UserEntity check = userRepo.findByEmail(email).orElse(null);
            if (check == null) {
                return Map.of("success", false, "message", "User account not found.");
            }
            String blockErr = getBlockMessageIfActive(check);
            if (blockErr != null) {
                return Map.of("success", false, "message", blockErr);
            }
            if (depositRepo.existsActiveRef(cleanUtr, GameEnums.DepositStatus.REJECTED)) {
                return Map.of("success", false, "message", "This UTR / Transaction Reference was already submitted.");
            }

            depositId = "DEP_" + System.currentTimeMillis() + Integer.toHexString(RANDOM.nextInt(0xFFFF));
            userMap = txTemplate.execute(status -> {
                UserEntity u = userRepo.findByEmail(email).orElseThrow();
                BigDecimal depBd = BigDecimal.valueOf(amount);

                DepositRequestEntity dr = new DepositRequestEntity();
                dr.setId(depositId);
                dr.setEmail(u.getEmail());
                dr.setUserId(u.getUserId());
                dr.setUsername(u.getUsername());
                dr.setAmount(depBd);
                dr.setMethod(cleanMethod);
                dr.setUtrOrPaymentId(cleanUtr);
                dr.setProofImage(proofImage);
                dr.setStatus(GameEnums.DepositStatus.PENDING);
                dr.setRequestedAt(Instant.now());
                depositRepo.save(dr);

                TransactionEntity tx = new TransactionEntity();
                tx.setId(depositId);
                tx.setEmail(email);
                tx.setTxType(GameEnums.TxType.DEPOSIT);
                tx.setMethod(cleanMethod);
                tx.setAccountOrUtr(cleanUtr);
                tx.setAmount(depBd);
                tx.setStatus("PENDING (Admin Verify)");
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
        return Map.of(
                "success", true,
                "depositId", depositId,
                "message", "Deposit request of ₹" + amount + " submitted! Real Balance will be credited after verification."
        );
    }

    public Map<String, Object> processDepositAction(String requestId, String action) {
        Map<String, Object> updatedUserMap = null;
        Map<String, Object> notificationMap = null;
        String targetEmail = null;
        Map<String, Object> adminSnapshot;

        stateLock.lock();
        try {
            DepositRequestEntity existing = depositRepo.findById(requestId).orElse(null);
            if (existing == null) {
                return Map.of("success", false, "message", "Deposit request not found.");
            }
            if (existing.getStatus() != GameEnums.DepositStatus.PENDING) {
                return Map.of("success", false, "message", "Deposit request already processed.");
            }
            targetEmail = existing.getEmail();
            final String fEmail = targetEmail;
            AtomicReference<Map<String, Object>> notifRef = new AtomicReference<>();

            updatedUserMap = txTemplate.execute(status -> {
                DepositRequestEntity dr = depositRepo.findById(requestId).orElseThrow();
                UserEntity u = userRepo.findByEmail(fEmail).orElse(null);
                TransactionEntity tx = txRepo.findById(dr.getId()).orElse(null);

                if ("APPROVED".equalsIgnoreCase(action)) {
                    dr.setStatus(GameEnums.DepositStatus.APPROVED);
                    dr.setProcessedAt(Instant.now());
                    PlatformSettingsEntity s = loadOrInitSettings();
                    s.setTotalRecharges(s.getTotalRecharges().add(dr.getAmount()));
                    settingsRepo.save(s);

                    if (u != null) {
                        u.setRealBalance(u.getRealBalance().add(dr.getAmount()));
                        u.setTotalRecharged(u.getTotalRecharged().add(dr.getAmount()));
                        u.setLastRechargeAt(Instant.now());
                        u.setLastRechargeAmt(dr.getAmount());
                        userRepo.save(u);
                    }
                    if (tx != null) {
                        tx.setStatus("SUCCESS");
                        txRepo.save(tx);
                    }
                    NotificationEntity n = createNotification(
                            fEmail,
                            GameEnums.NotificationType.DEPOSIT_APPROVED,
                            "Deposit Credited!",
                            "₹" + dr.getAmount().longValue() + " deposit (UTR: " + dr.getUtrOrPaymentId() + ") has been verified and credited to your Real Balance.",
                            dr.getAmount(),
                            dr.getId()
                    );
                    notifRef.set(payloadBuilder.toNotificationMap(n));
                } else if ("REJECTED".equalsIgnoreCase(action)) {
                    dr.setStatus(GameEnums.DepositStatus.REJECTED);
                    dr.setProcessedAt(Instant.now());
                    if (tx != null) {
                        tx.setStatus("REJECTED");
                        txRepo.save(tx);
                    }
                    NotificationEntity n = createNotification(
                            fEmail,
                            GameEnums.NotificationType.DEPOSIT_REJECTED,
                            "Deposit Rejected",
                            "Your deposit of ₹" + dr.getAmount().longValue() + " (UTR: " + dr.getUtrOrPaymentId() + ") could not be verified.",
                            dr.getAmount(),
                            dr.getId()
                    );
                    notifRef.set(payloadBuilder.toNotificationMap(n));
                }
                depositRepo.save(dr);
                return u != null ? buildSanitizedUser(u) : null;
            });
            notificationMap = notifRef.get();
            adminSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }

        if (targetEmail != null && updatedUserMap != null) {
            wsHub.emitToUser(targetEmail, "user_updated", updatedUserMap);
        }
        if (targetEmail != null && notificationMap != null) {
            wsHub.emitToUser(targetEmail, "notification", notificationMap);
        }
        wsHub.emitToAdmin("admin_live_update", adminSnapshot);
        return Map.of("success", true, "message", "Deposit request marked as " + action + "!");
    }

    /**
     * Completes a cryptographically verified Razorpay deposit idempotently.
     */
    public Map<String, Object> completeRazorpayDeposit(String email, String orderId, String paymentId) {
        Map<String, Object> updatedUserMap;
        Map<String, Object> notificationMap;
        Map<String, Object> adminSnapshot;
        long creditedAmt;

        stateLock.lock();
        try {
            DepositRequestEntity existing = depositRepo.findByOrderId(orderId).orElse(null);
            if (existing == null || !email.equals(existing.getEmail())) {
                return Map.of("success", false, "message", "Razorpay order not found for user.");
            }
            if (existing.getStatus() == GameEnums.DepositStatus.APPROVED) {
                return Map.of("success", true, "message", "Payment already credited.", "alreadyProcessed", true);
            }
            creditedAmt = existing.getAmount().longValue();
            AtomicReference<Map<String, Object>> notifRef = new AtomicReference<>();

            updatedUserMap = txTemplate.execute(status -> {
                DepositRequestEntity dr = depositRepo.findById(existing.getId()).orElseThrow();
                dr.setStatus(GameEnums.DepositStatus.APPROVED);
                dr.setUtrOrPaymentId(paymentId);
                dr.setProcessedAt(Instant.now());
                depositRepo.save(dr);

                UserEntity u = userRepo.findByEmail(email).orElseThrow();
                u.setRealBalance(u.getRealBalance().add(dr.getAmount()));
                u.setTotalRecharged(u.getTotalRecharged().add(dr.getAmount()));
                u.setLastRechargeAt(Instant.now());
                u.setLastRechargeAmt(dr.getAmount());
                userRepo.save(u);

                PlatformSettingsEntity s = loadOrInitSettings();
                s.setTotalRecharges(s.getTotalRecharges().add(dr.getAmount()));
                settingsRepo.save(s);

                TransactionEntity tx = new TransactionEntity();
                tx.setId(dr.getId());
                tx.setEmail(email);
                tx.setTxType(GameEnums.TxType.DEPOSIT);
                tx.setMethod("RAZORPAY");
                tx.setAccountOrUtr(paymentId);
                tx.setAmount(dr.getAmount());
                tx.setStatus("SUCCESS");
                tx.setCreatedAt(Instant.now());
                txRepo.save(tx);

                NotificationEntity n = createNotification(
                        email,
                        GameEnums.NotificationType.DEPOSIT_APPROVED,
                        "Recharge Successful!",
                        "₹" + dr.getAmount().longValue() + " added via Razorpay (" + paymentId + ") to your Real Balance.",
                        dr.getAmount(),
                        dr.getId()
                );
                notifRef.set(payloadBuilder.toNotificationMap(n));

                return buildSanitizedUser(u);
            });
            notificationMap = notifRef.get();
            adminSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }

        wsHub.emitToUser(email, "user_updated", updatedUserMap);
        if (notificationMap != null) {
            wsHub.emitToUser(email, "notification", notificationMap);
        }
        wsHub.emitToAdmin("admin_live_update", adminSnapshot);
        return Map.of("success", true, "message", "Razorpay payment verified! ₹" + creditedAmt + " credited to Real Balance.");
    }

    /**
     * Universal automated deposit completion (used by UPI Gateway Webhook, Bank SMS Webhook, and PayPal).
     */
    public Map<String, Object> completeAutomatedDeposit(
            String orderIdOrRef,
            String paymentRef,
            String method,
            Integer amountPaid,
            String note
    ) {
        Map<String, Object> updatedUserMap = null;
        Map<String, Object> notificationMap = null;
        Map<String, Object> adminSnapshot = null;
        String userEmail = null;
        long creditedAmt = 0L;

        stateLock.lock();
        try {
            DepositRequestEntity target = null;
            if (orderIdOrRef != null && !orderIdOrRef.isBlank()) {
                target = depositRepo.findByOrderId(orderIdOrRef.trim()).orElse(null);
                if (target == null) {
                    target = depositRepo.findById(orderIdOrRef.trim()).orElse(null);
                }
            }
            if (target == null && paymentRef != null && !paymentRef.isBlank()) {
                List<DepositRequestEntity> pending = depositRepo.findAllByOrderByRequestedAtDesc().stream()
                        .filter(d -> d.getStatus() == GameEnums.DepositStatus.PENDING)
                        .filter(d -> paymentRef.equalsIgnoreCase(d.getUtrOrPaymentId()))
                        .toList();
                if (!pending.isEmpty()) {
                    target = pending.get(0);
                }
            }
            if (target == null && amountPaid != null && amountPaid > 0) {
                Instant threshold = Instant.now().minusSeconds(1800);
                List<DepositRequestEntity> matching = depositRepo.findAllByOrderByRequestedAtDesc().stream()
                        .filter(d -> d.getStatus() == GameEnums.DepositStatus.PENDING)
                        .filter(d -> d.getAmount().intValue() == amountPaid)
                        .filter(d -> d.getRequestedAt().isAfter(threshold))
                        .toList();
                if (!matching.isEmpty()) {
                    target = matching.get(0);
                }
            }

            if (target == null) {
                return Map.of("success", false, "message", "Pending deposit request not found for ref: " + orderIdOrRef);
            }

            if (target.getStatus() == GameEnums.DepositStatus.APPROVED) {
                return Map.of("success", true, "message", "Deposit was already credited.", "alreadyProcessed", true);
            }

            userEmail = target.getEmail();
            creditedAmt = target.getAmount().longValue();
            final DepositRequestEntity finalTarget = target;
            AtomicReference<Map<String, Object>> notifRef = new AtomicReference<>();

            updatedUserMap = txTemplate.execute(status -> {
                DepositRequestEntity dr = depositRepo.findById(finalTarget.getId()).orElseThrow();
                dr.setStatus(GameEnums.DepositStatus.APPROVED);
                if (paymentRef != null && !paymentRef.isBlank()) {
                    dr.setUtrOrPaymentId(paymentRef.trim());
                }
                if (method != null && !method.isBlank()) {
                    dr.setMethod(method.trim().toUpperCase());
                }
                if (note != null && !note.isBlank()) {
                    dr.setAdminNote(note.trim());
                }
                dr.setProcessedAt(Instant.now());
                depositRepo.save(dr);

                UserEntity u = userRepo.findByEmail(dr.getEmail()).orElseThrow();
                u.setRealBalance(u.getRealBalance().add(dr.getAmount()));
                u.setTotalRecharged(u.getTotalRecharged().add(dr.getAmount()));
                u.setLastRechargeAt(Instant.now());
                u.setLastRechargeAmt(dr.getAmount());
                userRepo.save(u);

                PlatformSettingsEntity s = loadOrInitSettings();
                s.setTotalRecharges(s.getTotalRecharges().add(dr.getAmount()));
                settingsRepo.save(s);

                TransactionEntity tx = new TransactionEntity();
                tx.setId(dr.getId());
                tx.setEmail(dr.getEmail());
                tx.setTxType(GameEnums.TxType.DEPOSIT);
                tx.setMethod(dr.getMethod());
                tx.setAccountOrUtr(dr.getUtrOrPaymentId());
                tx.setAmount(dr.getAmount());
                tx.setStatus("SUCCESS");
                tx.setCreatedAt(Instant.now());
                txRepo.save(tx);

                String curSym = "USD".equalsIgnoreCase(s.getActiveCurrency()) ? "$" : "₹";
                NotificationEntity n = createNotification(
                        dr.getEmail(),
                        GameEnums.NotificationType.DEPOSIT_APPROVED,
                        "Recharge Confirmed!",
                        curSym + dr.getAmount().longValue() + " auto-credited via " + dr.getMethod() + " (" + (paymentRef != null ? paymentRef : "Instant") + ").",
                        dr.getAmount(),
                        dr.getId()
                );
                notifRef.set(payloadBuilder.toNotificationMap(n));
                return buildSanitizedUser(u);
            });
            notificationMap = notifRef.get();
            adminSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }

        if (userEmail != null) {
            wsHub.emitToUser(userEmail, "user_updated", updatedUserMap);
            if (notificationMap != null) {
                wsHub.emitToUser(userEmail, "notification", notificationMap);
            }
        }
        wsHub.emitToAdmin("admin_live_update", adminSnapshot);

        return Map.of(
                "success", true,
                "message", "Payment verified and credited: " + creditedAmt,
                "amount", creditedAmt,
                "email", userEmail != null ? userEmail : ""
        );
    }

    public Map<String, Object> submitWithdrawal(String email, Integer amount, String upiOrBank) {
        return submitWithdrawal(email, amount, "AUTO", null, null, null, null, null, upiOrBank);
    }

    public Map<String, Object> submitWithdrawal(
            String email,
            Integer amount,
            String withdrawMethod,
            String upiId,
            String accountHolderName,
            String bankAccountNumber,
            String ifscCode,
            String bankName,
            String fallbackUpiOrBank
    ) {
        String amtErr = validateWithdrawAmount(amount);
        if (amtErr != null) {
            return Map.of("success", false, "message", amtErr);
        }

        String reqMethod = withdrawMethod == null ? "AUTO" : withdrawMethod.trim().toUpperCase();
        String cleanHolder = accountHolderName == null ? "" : accountHolderName.trim();
        String cleanUpi = upiId == null ? "" : upiId.trim();
        String cleanAccNo = bankAccountNumber == null ? "" : bankAccountNumber.replaceAll("\\s+", "").trim();
        String cleanIfsc = ifscCode == null ? "" : ifscCode.trim().toUpperCase();
        String cleanBank = bankName == null ? "" : bankName.trim();

        String resolvedMethod;
        String cleanAcct;
        if ("BANK".equals(reqMethod)) {
            if (cleanHolder.length() < 2) {
                return Map.of("success", false, "message", "Please enter the Bank Account Holder Name.");
            }
            if (!cleanAccNo.matches("^\\d{9,18}$")) {
                return Map.of("success", false, "message", "Please enter a valid Bank Account Number (9 to 18 digits).");
            }
            if (!IFSC_PATTERN.matcher(cleanIfsc).matches()) {
                return Map.of("success", false, "message", "Please enter a valid 11-character IFSC Code (e.g. HDFC0001234).");
            }
            resolvedMethod = "BANK";
            cleanAcct = "BANK | Holder: " + cleanHolder + " | A/C: " + cleanAccNo + " | IFSC: " + cleanIfsc
                    + (cleanBank.isEmpty() ? "" : " | Bank: " + cleanBank);
        } else if ("UPI".equals(reqMethod)) {
            if (cleanUpi.length() < 5 || !cleanUpi.contains("@") || cleanUpi.contains(" ")) {
                return Map.of("success", false, "message", "Please enter a valid UPI ID (e.g. name@okhdfcbank).");
            }
            resolvedMethod = "UPI";
            cleanAcct = "UPI: " + cleanUpi + (cleanHolder.isEmpty() ? "" : " | Name: " + cleanHolder);
        } else {
            cleanAcct = fallbackUpiOrBank == null ? "" : fallbackUpiOrBank.trim();
            if (cleanAcct.length() < 4) {
                return Map.of("success", false, "message", "Please enter a valid UPI ID or Bank details.");
            }
            resolvedMethod = cleanAcct.toUpperCase().contains("IFSC") ? "BANK" : "UPI";
        }

        Map<String, Object> userMap;
        Map<String, Object> adminSnapshot;
        String reqId;

        stateLock.lock();
        try {
            UserEntity check = userRepo.findByEmail(email).orElse(null);
            if (check == null) {
                return Map.of("success", false, "message", "User not found.");
            }
            String blockErr = getBlockMessageIfActive(check);
            if (blockErr != null) {
                return Map.of("success", false, "message", blockErr);
            }

            BigDecimal wthBd = BigDecimal.valueOf(amount);
            if (check.getRealBalance().compareTo(wthBd) < 0) {
                return Map.of("success", false, "message", "Insufficient Real Balance to withdraw!");
            }

            reqId = "WTH_" + System.currentTimeMillis() + Integer.toHexString(RANDOM.nextInt(0xFFFF));
            userMap = txTemplate.execute(status -> {
                UserEntity u = userRepo.findByEmail(email).orElseThrow();
                u.setRealBalance(u.getRealBalance().subtract(wthBd));
                userRepo.save(u);

                WithdrawRequestEntity wr = new WithdrawRequestEntity();
                wr.setId(reqId);
                wr.setEmail(u.getEmail());
                wr.setUserId(u.getUserId());
                wr.setUsername(u.getUsername());
                wr.setAmount(wthBd);
                wr.setAccountDetails(cleanAcct);
                wr.setWithdrawMethod(resolvedMethod);
                wr.setUpiId(cleanUpi.isEmpty() ? null : cleanUpi);
                wr.setAccountHolderName(cleanHolder.isEmpty() ? null : cleanHolder);
                wr.setBankAccountNumber(cleanAccNo.isEmpty() ? null : cleanAccNo);
                wr.setIfscCode(cleanIfsc.isEmpty() ? null : cleanIfsc);
                wr.setBankName(cleanBank.isEmpty() ? null : cleanBank);
                wr.setStatus(GameEnums.WithdrawStatus.PENDING);
                wr.setRequestedAt(Instant.now());
                withdrawRepo.save(wr);

                TransactionEntity tx = new TransactionEntity();
                tx.setId(reqId);
                tx.setEmail(email);
                tx.setTxType(GameEnums.TxType.WITHDRAWAL);
                tx.setMethod(resolvedMethod);
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
        return Map.of(
                "success", true,
                "requestId", reqId,
                "message", "Withdrawal request for ₹" + amount + " (" + resolvedMethod + ") sent to Admin! Status: Pending."
        );
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
                if ("LOCKED".equals(currentStatus)) {
                    lockedWinningNumber = computeUpcomingWinningNumber(s);
                }
                return s.getOutcomeMode().name();
            });
            finalForced = forcedNextResult;
            adminSnapshot = buildAdminAnalyticsSnapshot();
            if ("LOCKED".equals(currentStatus)) {
                logLockedPreview(adminSnapshot);
            }
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

    public Map<String, Object> updateGameConfig(Integer cycleSeconds, Integer closeSeconds, Integer minProfitPct) {
        int cycle = cycleSeconds != null ? cycleSeconds : 30;
        int close = closeSeconds != null ? closeSeconds : 5;
        int margin = minProfitPct != null ? minProfitPct : 15;

        if (cycle < 15 || cycle > 300) {
            return Map.of("success", false, "message", "Cycle time must be between 15 and 300 seconds.");
        }
        if (close < 3 || close >= cycle) {
            return Map.of("success", false, "message", "Betting close time must be between 3 seconds and less than Cycle time (" + cycle + "s).");
        }
        if (margin < 0 || margin > 100) {
            return Map.of("success", false, "message", "Admin profit margin % must be between 0 and 100.");
        }

        Map<String, Object> tickPayload;
        Map<String, Object> adminSnapshot;

        stateLock.lock();
        try {
            txTemplate.executeWithoutResult(status -> {
                PlatformSettingsEntity s = loadOrInitSettings();
                s.setCycleDurationSeconds(cycle);
                s.setBetCloseSeconds(close);
                s.setAdminMinProfitPercent(margin);
                settingsRepo.save(s);

                if ("BETTING".equals(currentStatus)) {
                    if (timeLeft > cycle) {
                        timeLeft = cycle;
                    }
                    if (timeLeft <= close && timeLeft > 0) {
                        currentStatus = "LOCKED";
                        lockedWinningNumber = computeUpcomingWinningNumber(s);
                    }
                } else if ("LOCKED".equals(currentStatus)) {
                    lockedWinningNumber = computeUpcomingWinningNumber(s);
                }
            });

            tickPayload = new LinkedHashMap<>();
            tickPayload.put("period", currentPeriod);
            tickPayload.put("timeLeft", timeLeft);
            tickPayload.put("status", currentStatus);
            tickPayload.put("activeBetsCount", currentBets.size());
            tickPayload.put("cycleDurationSeconds", cycle);
            tickPayload.put("betCloseSeconds", close);

            adminSnapshot = buildAdminAnalyticsSnapshot();
            if ("LOCKED".equals(currentStatus)) {
                logLockedPreview(adminSnapshot);
            }
        } finally {
            stateLock.unlock();
        }

        wsHub.emitAll("game_tick", tickPayload);
        wsHub.emitToAdmin("admin_live_update", adminSnapshot);
        return Map.of(
                "success", true,
                "message", "Game config updated! Cycle: " + cycle + "s | Close: " + close + "s | Margin: " + margin + "%"
        );
    }

    public String validateDepositAmount(Integer amount) {
        PlatformSettingsEntity s = loadOrInitSettings();
        return validateAmountRange(amount, s.getMinDepositAmount(), s.getMaxDepositAmount(), "deposit");
    }

    public String validateWithdrawAmount(Integer amount) {
        PlatformSettingsEntity s = loadOrInitSettings();
        return validateAmountRange(amount, s.getMinWithdrawAmount(), s.getMaxWithdrawAmount(), "withdrawal");
    }

    private String validateAmountRange(Integer amount, int min, int max, String label) {
        if (amount == null || amount < min) {
            return "Minimum " + label + " amount is ₹" + min + ".";
        }
        if (amount > max) {
            return "Maximum " + label + " amount is ₹" + max + ".";
        }
        return null;
    }

    private String validateMinMaxConfig(int minVal, int maxVal, String label) {
        if (minVal < 10) {
            return "Minimum " + label + " amount must be at least ₹10.";
        }
        if (maxVal < minVal) {
            return "Maximum " + label + " amount (₹" + maxVal + ") cannot be less than Minimum (₹" + minVal + ").";
        }
        if (maxVal > 10_000_000) {
            return "Maximum " + label + " amount cannot exceed ₹10,000,000.";
        }
        return null;
    }

    public Map<String, Object> updatePaymentConfig(String upiId, String payeeName, String rzpKeyId, String rzpKeySecret) {
        return updatePaymentConfig(upiId, payeeName, rzpKeyId, rzpKeySecret, null, null, null, null);
    }

    public Map<String, Object> updatePaymentConfig(
            String upiId,
            String payeeName,
            String rzpKeyId,
            String rzpKeySecret,
            Integer minDeposit,
            Integer maxDeposit,
            Integer minWithdraw,
            Integer maxWithdraw
    ) {
        return updatePaymentConfig(upiId, payeeName, rzpKeyId, rzpKeySecret, minDeposit, maxDeposit, minWithdraw, maxWithdraw,
                null, null, null, null, null, null);
    }

    public Map<String, Object> updatePaymentConfig(
            String upiId,
            String payeeName,
            String rzpKeyId,
            String rzpKeySecret,
            Integer minDeposit,
            Integer maxDeposit,
            Integer minWithdraw,
            Integer maxWithdraw,
            String activeCurrency,
            String upiWebhookSecret,
            String smsWebhookToken,
            String paypalClientId,
            String paypalClientSecret,
            String paypalMode
    ) {
        Map<String, Object> adminSnapshot;
        Map<String, Object> publicPayCfg = new LinkedHashMap<>();
        String validationError = null;
        stateLock.lock();
        try {
            PlatformSettingsEntity current = loadOrInitSettings();
            int nextMinDep = minDeposit != null ? minDeposit : current.getMinDepositAmount();
            int nextMaxDep = maxDeposit != null ? maxDeposit : current.getMaxDepositAmount();
            int nextMinWth = minWithdraw != null ? minWithdraw : current.getMinWithdrawAmount();
            int nextMaxWth = maxWithdraw != null ? maxWithdraw : current.getMaxWithdrawAmount();

            validationError = validateMinMaxConfig(nextMinDep, nextMaxDep, "recharge");
            if (validationError == null) {
                validationError = validateMinMaxConfig(nextMinWth, nextMaxWth, "withdraw");
            }
            if (validationError != null) {
                return Map.of("success", false, "message", validationError);
            }

            txTemplate.executeWithoutResult(status -> {
                PlatformSettingsEntity s = loadOrInitSettings();
                if (upiId != null && !upiId.isBlank()) {
                    s.setUpiId(upiId.trim());
                }
                if (payeeName != null && !payeeName.isBlank()) {
                    s.setUpiPayeeName(payeeName.trim());
                }
                if (rzpKeyId != null) {
                    s.setRazorpayKeyId(rzpKeyId.trim());
                }
                if (rzpKeySecret != null && !"********".equals(rzpKeySecret.trim())) {
                    s.setRazorpayKeySecret(rzpKeySecret.trim());
                }
                s.setMinDepositAmount(nextMinDep);
                s.setMaxDepositAmount(nextMaxDep);
                s.setMinWithdrawAmount(nextMinWth);
                s.setMaxWithdrawAmount(nextMaxWth);

                if (activeCurrency != null && !activeCurrency.isBlank()) {
                    s.setActiveCurrency(activeCurrency);
                }
                if (upiWebhookSecret != null) {
                    s.setUpiWebhookSecret(upiWebhookSecret.trim());
                }
                if (smsWebhookToken != null) {
                    s.setSmsWebhookToken(smsWebhookToken.trim());
                }
                if (paypalClientId != null) {
                    s.setPaypalClientId(paypalClientId.trim());
                }
                if (paypalClientSecret != null && !"********".equals(paypalClientSecret.trim())) {
                    s.setPaypalClientSecret(paypalClientSecret.trim());
                }
                if (paypalMode != null) {
                    s.setPaypalMode(paypalMode.trim());
                }

                settingsRepo.save(s);

                boolean rzpEnabled = s.getRazorpayKeyId() != null && !s.getRazorpayKeyId().isBlank()
                        && s.getRazorpayKeySecret() != null && !s.getRazorpayKeySecret().isBlank();
                boolean ppEnabled = s.getPaypalClientId() != null && !s.getPaypalClientId().isBlank()
                        && s.getPaypalClientSecret() != null && !s.getPaypalClientSecret().isBlank();

                publicPayCfg.put("success", true);
                publicPayCfg.put("razorpayEnabled", rzpEnabled);
                publicPayCfg.put("razorpayKeyId", rzpEnabled ? s.getRazorpayKeyId().trim() : "");
                publicPayCfg.put("paypalEnabled", ppEnabled);
                publicPayCfg.put("paypalClientId", ppEnabled ? s.getPaypalClientId().trim() : "");
                publicPayCfg.put("paypalMode", s.getPaypalMode());
                publicPayCfg.put("activeCurrency", s.getActiveCurrency());
                publicPayCfg.put("upiId", s.getUpiId());
                publicPayCfg.put("upiPayeeName", s.getUpiPayeeName());
                publicPayCfg.put("minDeposit", s.getMinDepositAmount());
                publicPayCfg.put("maxDeposit", s.getMaxDepositAmount());
                publicPayCfg.put("minWithdraw", s.getMinWithdrawAmount());
                publicPayCfg.put("maxWithdraw", s.getMaxWithdrawAmount());
            });
            adminSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }
        wsHub.emitToAdmin("admin_live_update", adminSnapshot);
        wsHub.emitAll("payment_config_updated", publicPayCfg);
        return Map.of("success", true, "message", "Payment gateways, Webhooks, Currency & Limits saved!");
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
        Map<String, Object> notificationMap = null;
        Map<String, Object> tickerEv = null;
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
            AtomicReference<Map<String, Object>> notifRef = new AtomicReference<>();
            AtomicReference<Map<String, Object>> tickerRef = new AtomicReference<>();

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

                    NotificationEntity n = createNotification(
                            fEmail,
                            GameEnums.NotificationType.WITHDRAW_PAID,
                            "Withdrawal Successful!",
                            "₹" + wr.getAmount().longValue() + " has been sent to " + wr.getAccountDetails() + ". Thank you for playing GB_Colors!",
                            wr.getAmount(),
                            wr.getId()
                    );
                    notifRef.set(payloadBuilder.toNotificationMap(n));

                    Map<String, Object> tEv = Map.of(
                            "id", "TKR_WTH_" + wr.getId(),
                            "text", "💰 " + maskEmail(fEmail) + " withdrew ₹" + formatInr(wr.getAmount().longValue()) + " via UPI",
                            "type", "REAL_WITHDRAW",
                            "timestamp", System.currentTimeMillis()
                    );
                    pushTickerEvent(tEv);
                    tickerRef.set(tEv);
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
                    NotificationEntity n = createNotification(
                            fEmail,
                            GameEnums.NotificationType.WITHDRAW_REJECTED,
                            "Withdrawal Rejected & Refunded",
                            "Your withdrawal of ₹" + wr.getAmount().longValue() + " was rejected. The full amount has been refunded to your Real Balance.",
                            wr.getAmount(),
                            wr.getId()
                    );
                    notifRef.set(payloadBuilder.toNotificationMap(n));
                }
                withdrawRepo.save(wr);
                return u != null ? buildSanitizedUser(u) : null;
            });

            notificationMap = notifRef.get();
            tickerEv = tickerRef.get();
            adminSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }

        if (targetEmail != null && updatedUserMap != null) {
            wsHub.emitToUser(targetEmail, "user_updated", updatedUserMap);
        }
        if (targetEmail != null && notificationMap != null) {
            wsHub.emitToUser(targetEmail, "notification", notificationMap);
        }
        if (tickerEv != null) {
            wsHub.emitAll("ticker_event", tickerEv);
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

    public Map<String, Object> blockUser(String email, String blockTypeStr, Integer durationHours, String reason) {
        GameEnums.BlockType type;
        try {
            type = GameEnums.BlockType.valueOf(String.valueOf(blockTypeStr).trim().toUpperCase());
        } catch (Exception ex) {
            return Map.of("success", false, "message", "Invalid block type. Choose TEMPORARY or PERMANENT.");
        }
        if (type == GameEnums.BlockType.NONE) {
            return unblockUser(email);
        }
        if (type == GameEnums.BlockType.TEMPORARY && (durationHours == null || durationHours < 1 || durationHours > 8760)) {
            return Map.of("success", false, "message", "Temporary block duration must be between 1 and 8760 hours.");
        }

        String cleanReason = (reason == null || reason.isBlank()) ? "Blocked by Administrator" : reason.trim();
        Map<String, Object> adminSnapshot;
        Instant until = type == GameEnums.BlockType.TEMPORARY
                ? Instant.now().plus(durationHours, ChronoUnit.HOURS)
                : null;

        stateLock.lock();
        try {
            UserEntity existing = userRepo.findByEmail(email).orElse(null);
            if (existing == null) {
                return Map.of("success", false, "message", "User not found.");
            }
            txTemplate.executeWithoutResult(status -> {
                UserEntity u = userRepo.findByEmail(email).orElseThrow();
                u.setBlockType(type);
                u.setBlockedUntil(until);
                u.setBlockReason(cleanReason);
                u.setBlockedAt(Instant.now());
                userRepo.save(u);
                sessionRepo.deleteByEmail(email);
            });
            adminSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }

        Map<String, Object> blockNotice = new LinkedHashMap<>();
        blockNotice.put("blockType", type.name());
        blockNotice.put("blockedUntil", until != null ? until.toEpochMilli() : null);
        blockNotice.put("reason", cleanReason);
        blockNotice.put("message", formatBlockMessage(type, until, cleanReason));

        wsHub.emitToUser(email, "account_blocked", blockNotice);
        wsHub.disconnectUser(email, "ACCOUNT_BLOCKED");
        wsHub.emitToAdmin("admin_live_update", adminSnapshot);

        return Map.of(
                "success", true,
                "message", type == GameEnums.BlockType.PERMANENT
                        ? "Permanently blocked " + email
                        : "Temporarily blocked " + email + " for " + durationHours + " hour(s)"
        );
    }

    public Map<String, Object> unblockUser(String email) {
        Map<String, Object> adminSnapshot;
        stateLock.lock();
        try {
            UserEntity existing = userRepo.findByEmail(email).orElse(null);
            if (existing == null) {
                return Map.of("success", false, "message", "User not found.");
            }
            txTemplate.executeWithoutResult(status -> {
                UserEntity u = userRepo.findByEmail(email).orElseThrow();
                u.setBlockType(GameEnums.BlockType.NONE);
                u.setBlockedUntil(null);
                u.setBlockReason(null);
                u.setBlockedAt(null);
                userRepo.save(u);
            });
            adminSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }

        wsHub.emitToAdmin("admin_live_update", adminSnapshot);
        return Map.of("success", true, "message", "Unblocked " + email + "!");
    }

    public String getBlockMessageIfActive(UserEntity user) {
        if (user == null || user.getBlockType() == GameEnums.BlockType.NONE) {
            return null;
        }
        if (user.getBlockType() == GameEnums.BlockType.TEMPORARY) {
            if (user.getBlockedUntil() == null || !user.getBlockedUntil().isAfter(Instant.now())) {
                // Expired temporary block -> auto clear
                txTemplate.executeWithoutResult(status -> {
                    userRepo.findByEmail(user.getEmail()).ifPresent(u -> {
                        u.setBlockType(GameEnums.BlockType.NONE);
                        u.setBlockedUntil(null);
                        u.setBlockReason(null);
                        u.setBlockedAt(null);
                        userRepo.save(u);
                    });
                });
                return null;
            }
        }
        return formatBlockMessage(user.getBlockType(), user.getBlockedUntil(), user.getBlockReason());
    }

    public void markNotificationsRead(String email, List<String> ids) {
        txTemplate.executeWithoutResult(status -> {
            List<NotificationEntity> unread = notificationRepo.findByEmailAndIsReadFalseOrderByCreatedAtAsc(email);
            for (NotificationEntity n : unread) {
                if (ids == null || ids.isEmpty() || ids.contains(n.getId())) {
                    n.setRead(true);
                    notificationRepo.save(n);
                }
            }
        });
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

    private NotificationEntity createNotification(
            String email,
            GameEnums.NotificationType type,
            String title,
            String message,
            BigDecimal amount,
            String referenceId
    ) {
        NotificationEntity n = new NotificationEntity();
        n.setId("NTF_" + System.currentTimeMillis() + Integer.toHexString(RANDOM.nextInt(0xFFFF)));
        n.setEmail(email);
        n.setType(type);
        n.setTitle(title);
        n.setMessage(message);
        n.setAmount(amount);
        n.setReferenceId(referenceId);
        n.setRead(false);
        n.setCreatedAt(Instant.now());
        return notificationRepo.save(n);
    }

    private PlatformSettingsEntity loadOrInitSettings() {
        return settingsRepo.findById(1).orElseGet(() -> {
            PlatformSettingsEntity s = new PlatformSettingsEntity();
            s.setId(1);
            s.setAdminEmail(envAdminEmail.trim().toLowerCase());
            s.setAdminPasswordHash(passwordService.hashPassword(envAdminPass));
            s.setOutcomeMode(GameEnums.OutcomeMode.SMART_PROFIT);
            s.setRoundCounter(26);
            s.setCycleDurationSeconds(30);
            s.setBetCloseSeconds(5);
            s.setAdminMinProfitPercent(15);
            s.setSmtpHost(envSmtpHost);
            s.setSmtpPort(envSmtpPort);
            s.setSmtpUser(envSmtpUser);
            s.setSmtpPass(envSmtpPass);
            s.setSmtpFromName(envSmtpFromName);
            s.setUpiId(envUpiId);
            s.setUpiPayeeName(envUpiPayeeName);
            s.setRazorpayKeyId(envRazorpayKeyId);
            s.setRazorpayKeySecret(envRazorpayKeySecret);
            return settingsRepo.save(s);
        });
    }

    public void ensureAdminPlayWalletExists() {
        stateLock.lock();
        try {
            txTemplate.executeWithoutResult(status -> {
                PlatformSettingsEntity s = loadOrInitSettings();
                ensureAdminPlayWallet(s.getAdminEmail());
            });
        } finally {
            stateLock.unlock();
        }
    }

    private void ensureAdminPlayWallet(String adminEmail) {
        if (adminEmail == null || adminEmail.isBlank()) return;
        String cleanEmail = adminEmail.trim().toLowerCase();
        UserEntity existingByEmail = userRepo.findByEmail(cleanEmail).orElse(null);
        if (existingByEmail != null) return;

        UserEntity legacyAdmin = userRepo.findByEmail("gbcolor.admin@gmail.com")
                .or(() -> userRepo.findByUserId("ADMIN001"))
                .orElse(null);
        if (legacyAdmin != null) {
            legacyAdmin.setEmail(cleanEmail);
            legacyAdmin.setUsername("Admin");
            legacyAdmin.setPasswordHash(passwordService.hashPassword(envAdminPass));
            userRepo.save(legacyAdmin);
            return;
        }

        UserEntity adminPlay = new UserEntity();
        adminPlay.setUserId("ADMIN001");
        adminPlay.setEmail(cleanEmail);
        adminPlay.setUsername("Admin");
        adminPlay.setPasswordHash(passwordService.hashPassword(envAdminPass));
        adminPlay.setVipLevel(99);
        adminPlay.setEmailVerified(true);
        adminPlay.setDemoBalance(BigDecimal.valueOf(5000));
        adminPlay.setRealBalance(BigDecimal.ZERO);
        adminPlay.setActiveMode(GameEnums.ActiveMode.DEMO);
        adminPlay.setCreatedAt(Instant.now());
        userRepo.save(adminPlay);
    }

    private void cleanupUsersKeepOnlyBadhri() {
        String allowedUserEmail = "badhrinarayanan79@gmail.com";
        String adminEmail = "gbcolor@admin.com";
        String adminUserId = "ADMIN001";

        UserEntity badhri = userRepo.findByEmail(allowedUserEmail)
                .or(() -> userRepo.findByUsername("Badhrinarayanan79"))
                .or(() -> userRepo.findByUsername("badhrinarayanan79"))
                .or(() -> userRepo.findByUserId("GB800761"))
                .orElse(null);

        if (badhri == null) {
            badhri = new UserEntity();
            badhri.setUserId("GB800761");
            badhri.setEmail(allowedUserEmail);
            badhri.setUsername("Badhrinarayanan79");
            badhri.setPasswordHash(passwordService.hashPassword("User@123"));
            badhri.setVipLevel(1);
            badhri.setEmailVerified(true);
            badhri.setMustChangePassword(false);
            badhri.setDemoBalance(BigDecimal.valueOf(5000));
            badhri.setRealBalance(BigDecimal.valueOf(5000));
            badhri.setActiveMode(GameEnums.ActiveMode.REAL);
            badhri.setCreatedAt(Instant.now());
            userRepo.save(badhri);
            System.out.println("Created single allowed user: " + allowedUserEmail + " (" + badhri.getUserId() + ")");
        } else {
            badhri.setEmail(allowedUserEmail);
            badhri.setUsername("Badhrinarayanan79");
            badhri.setEmailVerified(true);
            badhri.setMustChangePassword(false);
            badhri.setBlockType(GameEnums.BlockType.NONE);
            badhri.setBlockedUntil(null);
            badhri.setBlockReason(null);
            if (badhri.getRealBalance() == null || badhri.getRealBalance().compareTo(BigDecimal.valueOf(1000)) < 0) {
                badhri.setRealBalance(BigDecimal.valueOf(5000));
            }
            userRepo.save(badhri);
            System.out.println("Verified single allowed user: " + allowedUserEmail + " (" + badhri.getUserId() + ")");
        }

        // Delete all other users except Badhri and Admin
        List<UserEntity> allUsers = userRepo.findAll();
        for (UserEntity u : allUsers) {
            String uEmail = u.getEmail() == null ? "" : u.getEmail().trim().toLowerCase();
            String uId = u.getUserId() == null ? "" : u.getUserId().trim();

            if (allowedUserEmail.equalsIgnoreCase(uEmail)
                    || adminUserId.equalsIgnoreCase(uId)
                    || adminEmail.equalsIgnoreCase(uEmail)) {
                continue;
            }

            try {
                sessionRepo.deleteByEmail(uEmail);
            } catch (Exception ignored) {}

            try {
                userRepo.delete(u);
                System.out.println("Purged non-allowed user from DB: " + uEmail + " (UID: " + uId + ")");
            } catch (Exception ex) {
                System.err.println("Could not delete user " + uEmail + ": " + ex.getMessage());
            }
        }
    }

    public Map<String, Object> sendAdminNotification(
            String scopeStr,
            String rawTargetEmail,
            String rawTitle,
            String rawMessage,
            String sentByEmail
    ) {
        String cleanMsg = rawMessage == null ? "" : rawMessage.trim();
        if (cleanMsg.isEmpty()) {
            return Map.of("success", false, "message", "Notification message cannot be empty.");
        }
        if (cleanMsg.length() > 480) {
            cleanMsg = cleanMsg.substring(0, 480);
        }

        boolean isUserSpecific = "USER".equalsIgnoreCase(scopeStr);
        String cleanTarget = rawTargetEmail == null ? "" : rawTargetEmail.trim().toLowerCase();
        String defaultTitle = isUserSpecific ? "Message from Admin" : "Admin Announcement";
        String cleanTitle = (rawTitle == null || rawTitle.isBlank()) ? defaultTitle : rawTitle.trim();
        if (cleanTitle.length() > 140) {
            cleanTitle = cleanTitle.substring(0, 140);
        }

        List<Map.Entry<String, Map<String, Object>>> outbound = new ArrayList<>();
        Map<String, Object> adminSnapshot;
        int recipientCount;

        stateLock.lock();
        try {
            PlatformSettingsEntity settings = loadOrInitSettings();
            String adminEmail = settings.getAdminEmail() == null ? "" : settings.getAdminEmail().trim().toLowerCase();
            final String fTitle = cleanTitle;
            final String fMsg = cleanMsg;
            final String fSender = (sentByEmail == null || sentByEmail.isBlank()) ? adminEmail : sentByEmail.trim().toLowerCase();

            if (isUserSpecific) {
                UserEntity targetUser = userRepo.findByEmail(cleanTarget).orElse(null);
                if (targetUser == null || !targetUser.isEmailVerified() || adminEmail.equalsIgnoreCase(targetUser.getEmail())) {
                    return Map.of("success", false, "message", "Please select a valid verified user.");
                }
                NotificationEntity n = txTemplate.execute(status -> {
                    NotificationEntity created = createNotification(
                            targetUser.getEmail(),
                            GameEnums.NotificationType.ADMIN_DIRECT,
                            fTitle,
                            fMsg,
                            null,
                            null
                    );
                    saveAdminMessageLog("USER", targetUser.getEmail(), 1, fTitle, fMsg, fSender);
                    return created;
                });
                if (n != null) {
                    outbound.add(Map.entry(targetUser.getEmail(), payloadBuilder.toNotificationMap(n)));
                }
                recipientCount = 1;
            } else {
                List<UserEntity> allVerified = userRepo.findAll().stream()
                        .filter(u -> u.isEmailVerified() && !adminEmail.equalsIgnoreCase(u.getEmail()))
                        .toList();
                if (allVerified.isEmpty()) {
                    return Map.of("success", false, "message", "No verified users available to receive notifications yet.");
                }
                txTemplate.executeWithoutResult(status -> {
                    for (UserEntity u : allVerified) {
                        NotificationEntity created = createNotification(
                                u.getEmail(),
                                GameEnums.NotificationType.ADMIN_BROADCAST,
                                fTitle,
                                fMsg,
                                null,
                                null
                        );
                        outbound.add(Map.entry(u.getEmail(), payloadBuilder.toNotificationMap(created)));
                    }
                    saveAdminMessageLog("ALL", null, allVerified.size(), fTitle, fMsg, fSender);
                });
                recipientCount = allVerified.size();
            }
            adminSnapshot = buildAdminAnalyticsSnapshot();
        } finally {
            stateLock.unlock();
        }

        for (var entry : outbound) {
            wsHub.emitToUser(entry.getKey(), "notification", entry.getValue());
        }
        wsHub.emitToAdmin("admin_live_update", adminSnapshot);

        return Map.of(
                "success", true,
                "recipientCount", recipientCount,
                "message", isUserSpecific
                        ? "Notification sent to " + cleanTarget + "!"
                        : "Broadcast sent to " + recipientCount + " verified user(s)!"
        );
    }

    private void saveAdminMessageLog(
            String scope,
            String targetEmail,
            int recipientCount,
            String title,
            String message,
            String sentByEmail
    ) {
        AdminMessageLogEntity log = new AdminMessageLogEntity();
        log.setId("ADM_MSG_" + System.currentTimeMillis() + Integer.toHexString(RANDOM.nextInt(0xFFFF)));
        log.setScope(scope);
        log.setTargetEmail(targetEmail);
        log.setRecipientCount(recipientCount);
        log.setTitle(title);
        log.setMessage(message);
        log.setSentByEmail(sentByEmail);
        log.setCreatedAt(Instant.now());
        adminMsgLogRepo.save(log);
    }

    private Map<String, Object> buildSanitizedUser(UserEntity user) {
        List<BetEntity> bets = betRepo.findTop60ByEmailAndStatusNotOrderByCreatedAtDesc(
                user.getEmail(), GameEnums.BetStatus.ACTIVE
        );
        List<TransactionEntity> txs = txRepo.findTop100ByEmailOrderByCreatedAtDesc(user.getEmail());
        List<WithdrawRequestEntity> withdrawals = withdrawRepo.findTop50ByEmailOrderByRequestedAtDesc(user.getEmail());
        List<DepositRequestEntity> deposits = depositRepo.findTop50ByEmailOrderByRequestedAtDesc(user.getEmail());
        List<NotificationEntity> unreadNotifications = notificationRepo.findByEmailAndIsReadFalseOrderByCreatedAtAsc(user.getEmail());
        List<NotificationEntity> recentNotifications = notificationRepo.findTop30ByEmailOrderByCreatedAtDesc(user.getEmail());
        return payloadBuilder.sanitizeUser(user, bets, txs, withdrawals, deposits, unreadNotifications, recentNotifications);
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
        List<DepositRequestEntity> deposits = depositRepo.findAllByOrderByRequestedAtDesc();
        List<AdminMessageLogEntity> adminMsgs = adminMsgLogRepo.findTop50ByOrderByCreatedAtDesc();
        List<Map<String, Object>> roundHistory = getHistoryTop25();
        return payloadBuilder.buildAdminAnalyticsPayload(
                currentPeriod,
                timeLeft,
                currentStatus,
                forcedNextResult,
                lockedWinningNumber,
                settings,
                new ArrayList<>(currentBets),
                users,
                withdrawals,
                deposits,
                adminMsgs,
                roundHistory
        );
    }

    private void pushTickerEvent(Map<String, Object> ev) {
        liveWinTickerBuffer.addFirst(ev);
        while (liveWinTickerBuffer.size() > MAX_TICKER_HISTORY) {
            liveWinTickerBuffer.removeLast();
        }
    }

    private List<Map<String, Object>> getTickerEventsSnapshot() {
        return new ArrayList<>(liveWinTickerBuffer);
    }

    private Map<String, Object> buildRealWinTickerEvent(BetEntity bet) {
        String masked = maskEmail(bet.getEmail());
        long payout = bet.getPayout() != null ? bet.getPayout().longValue() : 0L;
        String label = bet.getBetType() == GameEnums.BetType.NUMBER
                ? "Number " + bet.getChoice()
                : bet.getChoice();
        return Map.of(
                "id", "TKR_" + bet.getId(),
                "text", "🎉 " + masked + " won ₹" + formatInr(payout) + " on " + label,
                "type", "REAL_WIN",
                "timestamp", bet.getCreatedAt() != null ? bet.getCreatedAt().toEpochMilli() : System.currentTimeMillis()
        );
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "player***";
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at);
        String prefix = local.length() <= 2 ? local : local.substring(0, Math.min(3, local.length()));
        return prefix + "***" + domain;
    }

    private String formatInr(long amount) {
        return String.format("%,d", amount);
    }

    private String formatBlockMessage(GameEnums.BlockType type, Instant until, String reason) {
        String r = (reason == null || reason.isBlank()) ? "Policy violation" : reason;
        if (type == GameEnums.BlockType.PERMANENT) {
            return "Your account is permanently blocked. Reason: " + r;
        }
        String untilStr = until != null ? until.toString() : "later";
        return "Your account is temporarily blocked until " + untilStr + ". Reason: " + r;
    }

    /**
     * Seeds ready-to-use local test accounts once so betting, Smart Profit, and withdrawals can be tested immediately.
     */
    private void seedSampleTestUsers() {
        // Disabled permanently: only Badhrinarayanan79 is retained
    }

    private void createSampleUserIfAbsent(
            String userId,
            String email,
            String username,
            String rawPassword,
            BigDecimal realBalance,
            BigDecimal demoBalance
    ) {
        if (userRepo.existsByEmail(email)) {
            return;
        }
        UserEntity user = new UserEntity();
        user.setUserId(userId);
        user.setEmail(email);
        user.setUsername(username);
        user.setPasswordHash(passwordService.hashPassword(rawPassword));
        user.setVipLevel(1);
        user.setDemoBalance(demoBalance);
        user.setRealBalance(realBalance);
        user.setTotalRecharged(realBalance);
        user.setActiveMode(GameEnums.ActiveMode.REAL);
        user.setCreatedAt(Instant.now());
        userRepo.save(user);
        System.out.println("[SAMPLE TEST USER] Created " + email + " (" + username + ") | Real: Rs."
                + realBalance + " | Demo: Rs." + demoBalance);
    }

    /**
     * Logs the pre-reveal upcoming winning result and house profit projection as soon as betting locks.
     */
    private void logLockedPreview(Map<String, Object> adminSnapshot) {
        if (adminSnapshot == null) {
            return;
        }
        Object raw = adminSnapshot.get("upcomingResult");
        if (!(raw instanceof Map<?, ?> u)) {
            return;
        }
        System.out.println(String.format(
                "[LOCKED PREVIEW] Period %s | Result: #%s (%s | %s) | Mode: %s | Pool (%s): Rs.%s | Payout: Rs.%s | House Profit: Rs.%s | Reveals in %ss",
                u.get("period"),
                u.get("number"),
                String.valueOf(u.get("colors")),
                String.valueOf(u.get("size")).toUpperCase(),
                u.get("modeUsed"),
                u.get("poolType"),
                u.get("totalCollected"),
                u.get("totalPayout"),
                u.get("houseProfit"),
                u.get("revealInSeconds")
        ));
    }
}
