package com.gbcolors;

import com.gbcolors.model.BetEntity;
import com.gbcolors.model.GameEnums;
import com.gbcolors.service.GameRuleEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameRuleEngineTest {

    private final GameRuleEngine engine = new GameRuleEngine();

    @Test
    void outcomePropsMatchColorAndSizeRules() {
        var zero = engine.getOutcomeProps(0);
        assertEquals(List.of("red", "violet"), zero.colors());
        assertEquals("small", zero.size());

        var five = engine.getOutcomeProps(5);
        assertEquals(List.of("green", "violet"), five.colors());
        assertEquals("big", five.size());

        var seven = engine.getOutcomeProps(7);
        assertEquals(List.of("green"), seven.colors());
        assertEquals("big", seven.size());
    }

    @Test
    void evaluateBetCalculatesCorrectPayoutMultipliers() {
        BetEntity greenBet = new BetEntity();
        greenBet.setBetType(GameEnums.BetType.COLOR);
        greenBet.setChoice("GREEN");
        greenBet.setAmount(BigDecimal.valueOf(100));

        assertEquals(200L, engine.evaluateBetOutcome(greenBet, 3).payout());
        assertEquals(150L, engine.evaluateBetOutcome(greenBet, 5).payout());
        assertEquals(0L, engine.evaluateBetOutcome(greenBet, 2).payout());

        BetEntity numBet = new BetEntity();
        numBet.setBetType(GameEnums.BetType.NUMBER);
        numBet.setChoice("7");
        numBet.setAmount(BigDecimal.valueOf(50));

        assertEquals(450L, engine.evaluateBetOutcome(numBet, 7).payout());
        assertEquals(0L, engine.evaluateBetOutcome(numBet, 1).payout());
    }

    @Test
    void smartProfitProjectionPrioritizesRealBetsAndMaximizesHouseProfit() {
        BetEntity realBigBet = new BetEntity();
        realBigBet.setBetType(GameEnums.BetType.SIZE);
        realBigBet.setChoice("BIG");
        realBigBet.setAmount(BigDecimal.valueOf(1000));
        realBigBet.setMode(GameEnums.ActiveMode.REAL);

        BetEntity realRedBet = new BetEntity();
        realRedBet.setBetType(GameEnums.BetType.COLOR);
        realRedBet.setChoice("RED");
        realRedBet.setAmount(BigDecimal.valueOf(500));
        realRedBet.setMode(GameEnums.ActiveMode.REAL);

        var summary = engine.computeRoundProjections(List.of(realBigBet, realRedBet));
        assertEquals("REAL", summary.targetMode());
        assertEquals(1500L, summary.totalCollected());
        assertEquals(1500L, summary.maxProfit());

        // Small + Green numbers (1, 3) pay 0 on BIG and 0 on RED => 1500 profit
        List<Integer> bestNums = summary.bestCandidates().stream()
                .map(GameRuleEngine.NumberProjection::number)
                .toList();
        assertEquals(List.of(1, 3), bestNums);
    }

    @Test
    void targetMarginPrioritizesAdminProfitFirstThenUserProfit() {
        BetEntity userARed = new BetEntity();
        userARed.setBetType(GameEnums.BetType.COLOR);
        userARed.setChoice("RED");
        userARed.setAmount(BigDecimal.valueOf(10));
        userARed.setMode(GameEnums.ActiveMode.REAL);

        BetEntity userBGreen = new BetEntity();
        userBGreen.setBetType(GameEnums.BetType.COLOR);
        userBGreen.setChoice("GREEN");
        userBGreen.setAmount(BigDecimal.valueOf(20));
        userBGreen.setMode(GameEnums.ActiveMode.REAL);

        var summary = engine.computeRoundProjections(List.of(userARed, userBGreen), 15);
        assertEquals("REAL", summary.targetMode());
        assertEquals(30L, summary.totalCollected());
        assertEquals(5L, summary.requiredMinProfit());

        // Pure RED (2, 4, 6, 8) yields +₹10 house profit (33.3% >= 15%) AND pays User A full 2x (₹20)
        List<Integer> bestNums = summary.bestCandidates().stream()
                .map(GameRuleEngine.NumberProjection::number)
                .toList();
        assertEquals(List.of(2, 4, 6, 8), bestNums);

        // Solo bettor on RED ₹10 -> Admin must never lose, so GREEN numbers win (+₹10 profit)
        var soloSummary = engine.computeRoundProjections(List.of(userARed), 15);
        List<Integer> soloBest = soloSummary.bestCandidates().stream()
                .map(GameRuleEngine.NumberProjection::number)
                .toList();
        assertEquals(List.of(1, 3, 5, 7, 9), soloBest);

        // Empty round reports targetMode NONE
        var emptySummary = engine.computeRoundProjections(List.of(), 15);
        assertEquals("NONE", emptySummary.targetMode());
    }
}
