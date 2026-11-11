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
            long houseProfit
    ) {}

    public record ProjectionSummary(
            List<NumberProjection> projections,
            List<NumberProjection> bestCandidates,
            long maxProfit,
            long totalCollected,
            String targetMode
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
        }

        double amt = bet.getAmount() != null ? bet.getAmount().doubleValue() : 0.0;
        long payout = win ? (long) Math.floor(amt * multiplier) : 0L;
        return new BetEvalResult(win, multiplier, payout);
    }

    public ProjectionSummary computeRoundProjections(List<BetEntity> allCurrentBets) {
        List<BetEntity> realBets = allCurrentBets.stream()
                .filter(b -> b.getMode() == GameEnums.ActiveMode.REAL)
                .toList();

        boolean hasReal = !realBets.isEmpty();
        List<BetEntity> targetBets = hasReal ? realBets : allCurrentBets;

        long totalCollected = targetBets.stream()
                .map(BetEntity::getAmount)
                .mapToLong(BigDecimal::longValue)
                .sum();

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
            OutcomeProps props = getOutcomeProps(num);
            projections.add(new NumberProjection(
                    num,
                    props.colors(),
                    props.size(),
                    totalCollected,
                    totalPayout,
                    houseProfit
            ));
        }

        final long finalMax = maxProfit == Long.MIN_VALUE ? 0L : maxProfit;
        List<NumberProjection> bestCandidates = projections.stream()
                .filter(p -> p.houseProfit() == finalMax)
                .toList();

        return new ProjectionSummary(
                projections,
                bestCandidates,
                finalMax,
                totalCollected,
                hasReal ? "REAL" : "DEMO"
        );
    }
}
