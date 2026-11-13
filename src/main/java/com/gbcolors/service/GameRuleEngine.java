package com.gbcolors.service;

import com.gbcolors.model.BetEntity;
import com.gbcolors.model.GameEnums;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure game rules: number/color/size mapping, payout math, and Smart Profit projections.
 */
@Component
public class GameRuleEngine {

    private static final DateTimeFormatter PERIOD_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public record OutcomeProps(List<String> colors, String size) {}

    public record BetEvalResult(boolean win, double multiplier, long payout) {}

    public record NumberProjection(
            int number,
            List<String> colors,
            String size,
            long totalCollected,
            long totalPayout,
            long houseProfit,
            boolean meetsMargin
    ) {}

    public record ProjectionSummary(
            List<NumberProjection> projections,
            List<NumberProjection> bestCandidates,
            long maxProfit,
            long totalCollected,
            String targetMode,
            int minProfitPercent,
            long requiredMinProfit
    ) {}

    public OutcomeProps getOutcomeProps(int num) {
        List<String> colors;
        if (num == 0) {
            colors = List.of("red", "violet");
        } else if (num == 5) {
            colors = List.of("green", "violet");
        } else if (num == 1 || num == 3 || num == 7 || num == 9) {
            colors = List.of("green");
        } else {
            colors = List.of("red");
        }
        String size = num >= 5 ? "big" : "small";
        return new OutcomeProps(colors, size);
    }

    public String generatePeriodString(int roundCounter) {
        return LocalDate.now().format(PERIOD_DATE_FMT) + String.format("%04d", roundCounter);
    }

    public BetEvalResult evaluateBetOutcome(BetEntity bet, int winningNumber) {
        OutcomeProps props = getOutcomeProps(winningNumber);
        boolean win = false;
        double multiplier = 0.0;
        String choice = bet.getChoice() == null ? "" : bet.getChoice().trim().toLowerCase();

        if (bet.getBetType() == GameEnums.BetType.COLOR) {
            switch (choice) {
                case "green" -> {
                    if (winningNumber == 5) {
                        win = true;
                        multiplier = 1.5;
                    } else if (winningNumber == 1 || winningNumber == 3 || winningNumber == 7 || winningNumber == 9) {
                        win = true;
                        multiplier = 2.0;
                    }
                }
                case "red" -> {
                    if (winningNumber == 0) {
                        win = true;
                        multiplier = 1.5;
                    } else if (winningNumber == 2 || winningNumber == 4 || winningNumber == 6 || winningNumber == 8) {
                        win = true;
                        multiplier = 2.0;
                    }
                }
                case "violet" -> {
                    if (winningNumber == 0 || winningNumber == 5) {
                        win = true;
                        multiplier = 4.5;
                    }
                }
                default -> {}
            }
        } else if (bet.getBetType() == GameEnums.BetType.NUMBER) {
            try {
                if (Integer.parseInt(choice) == winningNumber) {
                    win = true;
                    multiplier = 9.0;
                }
            } catch (NumberFormatException ignored) {}
        } else if (bet.getBetType() == GameEnums.BetType.SIZE) {
            if (choice.equals(props.size())) {
                win = true;
                multiplier = 2.0;
            }
        } else if (bet.getBetType() == GameEnums.BetType.SAFE_COLOR) {
            String winningColor = (winningNumber == 1 || winningNumber == 3 || winningNumber == 5 || winningNumber == 7 || winningNumber == 9)
                    ? "green" : "red";
            if (choice.equals(winningColor)) {
                win = true;
                multiplier = 1.5;
            } else {
                win = false;
                multiplier = 0.8;
            }
        }

        double amt = bet.getAmount() != null ? bet.getAmount().doubleValue() : 0.0;
        long payout = (long) Math.floor(amt * multiplier);
        return new BetEvalResult(win, multiplier, payout);
    }

    public ProjectionSummary computeRoundProjections(List<BetEntity> allCurrentBets) {
        return computeRoundProjections(allCurrentBets, 100);
    }

    /**
     * Two-priority Smart Profit projection:
     * 1st priority: Ensure Admin achieves at least {@code minProfitPercent}% house profit when possible
     * (or maximum available house profit if no outcome meets the target margin).
     * 2nd priority: Among qualifying outcomes that meet Admin's margin, maximize user payout so players
     * receive fair winning payouts (e.g., 2x colors) rather than forcing 1.5x Violet or zero-payout wipeouts.
     */
    public ProjectionSummary computeRoundProjections(List<BetEntity> allCurrentBets, int minProfitPercent) {
        List<BetEntity> realBets = allCurrentBets.stream()
                .filter(b -> b.getMode() == GameEnums.ActiveMode.REAL)
                .toList();

        boolean hasAny = !allCurrentBets.isEmpty();
        boolean hasReal = !realBets.isEmpty();
        List<BetEntity> targetBets = hasReal ? realBets : allCurrentBets;

        long totalCollected = targetBets.stream()
                .map(BetEntity::getAmount)
                .mapToLong(BigDecimal::longValue)
                .sum();

        int clampedMargin = Math.max(0, Math.min(100, minProfitPercent));
        long requiredMinProfit = 0L;
        if (totalCollected > 0 && clampedMargin > 0) {
            requiredMinProfit = (long) Math.ceil(totalCollected * (clampedMargin / 100.0));
            if (requiredMinProfit <= 0) {
                requiredMinProfit = 1L;
            }
        }

        List<NumberProjection> projections = new ArrayList<>(10);
        long maxProfit = Long.MIN_VALUE;

        for (int num = 0; num <= 9; num++) {
            long totalPayout = 0L;
            for (BetEntity bet : targetBets) {
                totalPayout += evaluateBetOutcome(bet, num).payout();
            }
            long houseProfit = totalCollected - totalPayout;
            if (houseProfit > maxProfit) {
                maxProfit = houseProfit;
            }
            boolean meetsMargin = totalCollected == 0 || houseProfit >= requiredMinProfit;
            OutcomeProps props = getOutcomeProps(num);
            projections.add(new NumberProjection(
                    num,
                    props.colors(),
                    props.size(),
                    totalCollected,
                    totalPayout,
                    houseProfit,
                    meetsMargin
            ));
        }

        final long finalMax = maxProfit == Long.MIN_VALUE ? 0L : maxProfit;
        List<NumberProjection> bestCandidates;
        if (!hasAny || totalCollected == 0) {
            bestCandidates = List.copyOf(projections);
        } else {
            List<NumberProjection> qualifying = projections.stream()
                    .filter(NumberProjection::meetsMargin)
                    .toList();
            if (!qualifying.isEmpty()) {
                long maxUserPayout = qualifying.stream()
                        .mapToLong(NumberProjection::totalPayout)
                        .max()
                        .orElse(0L);
                bestCandidates = qualifying.stream()
                        .filter(p -> p.totalPayout() == maxUserPayout)
                        .toList();
            } else {
                List<NumberProjection> highestProfit = projections.stream()
                        .filter(p -> p.houseProfit() == finalMax)
                        .toList();
                long maxTiePayout = highestProfit.stream()
                        .mapToLong(NumberProjection::totalPayout)
                        .max()
                        .orElse(0L);
                bestCandidates = highestProfit.stream()
                        .filter(p -> p.totalPayout() == maxTiePayout)
                        .toList();
            }
        }

        String targetMode = !hasAny ? "NONE" : (hasReal ? "REAL" : "DEMO");
        return new ProjectionSummary(
                projections,
                bestCandidates,
                finalMax,
                totalCollected,
                targetMode,
                clampedMargin,
                requiredMinProfit
        );
    }
}
