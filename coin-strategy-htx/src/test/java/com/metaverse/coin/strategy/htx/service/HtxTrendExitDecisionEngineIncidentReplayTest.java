package com.metaverse.coin.strategy.htx.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.metaverse.coin.strategy.htx.service.HtxTrendExitDecisionEngine.Action;
import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSide;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Replays the 2026-09-24 closed Fibonacci-short incident with frozen public 15-minute candles
 * and the production 15-minute signal snapshot. It intentionally starts from the first fully
 * completed candle after entry, so the entry candle cannot introduce intrabar look-ahead.
 */
class HtxTrendExitDecisionEngineIncidentReplayTest {

    private static final String FIXTURE = "/htx/replay/htx-trend-exit-incident-20260924.json";
    private static final BigDecimal TRAILING_ATR_MULTIPLIER = new BigDecimal("2");
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int SCALE = 12;

    private final HtxTrendExitDecisionEngine engine = new HtxTrendExitDecisionEngine();

    @Test
    void replaysClosedIncidentWithoutLookAheadAndRecordsUnarmedLossFallback() {
        JSONObject fixture = loadFixture();
        JSONArray positions = fixture.getJSONArray("positions");

        ReplayOutcome doge = replay(findPosition(positions, 153L));
        assertClose(doge, 1790195400L, new BigDecimal("0.092272"), new BigDecimal("2.5995279200"));
        assertEquals("MFE_DYNAMIC_PULLBACK", doge.trailingArmReason());

        ReplayOutcome sui = replay(findPosition(positions, 154L));
        assertClose(sui, 1790180100L, new BigDecimal("0.9588"), new BigDecimal("0.96718440"));
        assertEquals("RISK_REWARD_MILESTONE", sui.trailingArmReason());

        // ZEC finishes the frozen window in profit at its bar-close reference. XRP 156 and XRP 160
        // are loss-making, but each has only two fully completed post-entry bars. With production's
        // three-bar confirmation, all three remain deliberately unclosed in this no-look-ahead replay.
        assertHoldUntilHistoricalExit(replay(findPosition(positions, 156L)), 1790198100L);
        assertHoldUntilHistoricalExit(replay(findPosition(positions, 159L)), 1790246700L);
        assertHoldUntilHistoricalExit(replay(findPosition(positions, 160L)), 1790246700L);
    }

    private ReplayOutcome replay(JSONObject position) {
        String side = "SHORT";
        BigDecimal entryPrice = decimal(position, "entryPrice");
        BigDecimal initialStop = decimal(position, "initialStopPrice");
        BigDecimal invalidation = decimal(position, "invalidationPrice");
        BigDecimal structureTarget = decimal(position, "structureTargetPrice");
        BigDecimal contractSize = decimal(position, "contractSize");
        long contracts = position.getLongValue("contracts");
        BigDecimal entryFee = decimal(position, "entryFee");
        BigDecimal closeFeeRate = decimal(position, "closeFeeRate");
        BigDecimal highest = entryPrice;
        BigDecimal lowest = entryPrice;
        BigDecimal trailing = initialStop;
        BigDecimal profitLock = null;
        BigDecimal maxFavorableR = ZERO;
        LocalDateTime trailingArmedAt = null;
        String trailingArmReason = null;
        ReplayOutcome latest = null;
        int flatStreak = 0;
        int requiredFlatStreak = position.getIntValue("requiredFlatStreak");
        BigDecimal flatLossBudget = decimal(position, "flatLossBudget");

        for (Object rawBar : position.getJSONArray("bars")) {
            JSONObject bar = (JSONObject) rawBar;
            BigDecimal close = decimal(bar, "close");
            highest = highest.max(decimal(bar, "high"));
            lowest = lowest.min(decimal(bar, "low"));
            BigDecimal calculatedTrailing = lowest.add(decimal(bar, "atr").multiply(TRAILING_ATR_MULTIPLIER));
            trailing = trailing.min(calculatedTrailing);
            BigDecimal protection = profitLock == null ? trailing : trailing.min(profitLock);
            BigDecimal estimatedCloseFee = close.multiply(contractSize).multiply(BigDecimal.valueOf(contracts))
                    .multiply(closeFeeRate);
            BigDecimal currentNet = closeNetPnl(entryPrice, close, contractSize, contracts, entryFee, estimatedCloseFee);
            TrendSide signalSide = TrendSide.valueOf(bar.getString("signalSide"));
            flatStreak = signalSide == TrendSide.FLAT ? flatStreak + 1 : 0;
            HtxTrendExitDecisionEngine.Decision decision = engine.evaluate(new HtxTrendExitDecisionEngine.Input(
                    HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION, side, contracts, contractSize,
                    entryPrice, initialStop, invalidation, structureTarget, close, protection, highest, lowest,
                    decimal(bar, "atr"), signalSide, decimal(bar, "emaFast"),
                    decimal(bar, "emaSlow"), 0, false, currentNet, estimatedCloseFee, trailingArmedAt,
                    trailingArmReason, maxFavorableR, LocalDateTime.ofEpochSecond(bar.getLongValue("barTime"), 0,
                            java.time.ZoneOffset.ofHours(8)), flatStreak, requiredFlatStreak, flatLossBudget));
            trailingArmedAt = decision.trailingArmedAt();
            trailingArmReason = decision.trailingArmReason();
            maxFavorableR = decision.maxFavorableR();
            if (decision.protectionPrice() != null) {
                profitLock = profitLock == null ? decision.protectionPrice() : profitLock.min(decision.protectionPrice());
            }
            latest = new ReplayOutcome(bar.getLongValue("barTime"), close, currentNet, decision.action(),
                    decision.phase(), trailingArmReason);
            if (decision.action() == Action.FULL_CLOSE || decision.action() == Action.PARTIAL_CLOSE) {
                return latest;
            }
        }
        return latest;
    }

    private static BigDecimal closeNetPnl(BigDecimal entryPrice, BigDecimal closePrice, BigDecimal contractSize,
                                          long contracts, BigDecimal entryFee, BigDecimal closeFee) {
        BigDecimal quantity = contractSize.multiply(BigDecimal.valueOf(contracts));
        return entryPrice.subtract(closePrice).multiply(quantity).subtract(entryFee).subtract(closeFee);
    }

    private static void assertClose(ReplayOutcome outcome, long expectedBarTime, BigDecimal expectedPrice,
                                    BigDecimal expectedNetPnl) {
        assertNotNull(outcome);
        assertEquals(Action.FULL_CLOSE, outcome.action());
        assertEquals(expectedBarTime, outcome.barTime());
        assertEquals(0, outcome.referencePrice().compareTo(expectedPrice));
        assertEquals(0, outcome.netPnl().setScale(SCALE, RoundingMode.HALF_UP)
                .compareTo(expectedNetPnl.setScale(SCALE, RoundingMode.HALF_UP)));
    }

    private static void assertHoldUntilHistoricalExit(ReplayOutcome outcome, long expectedBarTime) {
        assertNotNull(outcome);
        assertEquals(expectedBarTime, outcome.barTime());
        assertEquals(Action.HOLD, outcome.action());
    }

    private static JSONObject loadFixture() {
        try (InputStream input = HtxTrendExitDecisionEngineIncidentReplayTest.class.getResourceAsStream(FIXTURE)) {
            return JSON.parseObject(Objects.requireNonNull(input, "incident replay fixture is missing"));
        } catch (Exception exception) {
            throw new IllegalStateException("unable to read incident replay fixture", exception);
        }
    }

    private static JSONObject findPosition(JSONArray positions, long id) {
        for (Object value : positions) {
            JSONObject position = (JSONObject) value;
            if (position.getLongValue("id") == id) {
                return position;
            }
        }
        throw new IllegalArgumentException("incident position is missing id=" + id);
    }

    private static BigDecimal decimal(JSONObject value, String key) {
        return new BigDecimal(value.getString(key));
    }

    private record ReplayOutcome(long barTime, BigDecimal referencePrice, BigDecimal netPnl, Action action,
                                 String phase, String trailingArmReason) {
    }
}
