package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.CoinStrategyLedgerEventEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.CoinStrategyLedgerEventMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTradeAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Projects strategy-specific execution records into the unified strategy ledger.
 *
 * <p>Only economic delta events should contribute to net PnL aggregation: FEE rows write
 * negative {@code netPnlAfterFee} and REALIZED_PNL rows write gross realized profit. Fill
 * rows keep turnover only, so dashboards can sum {@code netPnlAfterFee} without double
 * counting the same position.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoinStrategyLedgerRecorder {
    public static final String EVENT_POSITION_INTENT = "POSITION_INTENT";
    public static final String EVENT_OPEN_ORDER = "OPEN_ORDER";
    public static final String EVENT_CLOSE_ORDER = "CLOSE_ORDER";
    public static final String EVENT_OPEN_FILL = "OPEN_FILL";
    public static final String EVENT_CLOSE_FILL = "CLOSE_FILL";
    public static final String EVENT_FEE = "FEE";
    public static final String EVENT_REALIZED_PNL = "REALIZED_PNL";

    private static final String EXCHANGE_HTX = "HTX";
    private static final String MARKET_USDT_SWAP = "USDT_SWAP";
    private static final String STRATEGY_FAMILY_TREND = "TREND";
    private static final String STRATEGY_NAME_HTX_TREND = "HTX_TREND_V1";
    private static final String MODEL_FIB_PULLBACK = "FIB_PULLBACK_CONTINUATION";
    private static final String MODEL_MTF_MOMENTUM = "MULTI_TIMEFRAME_MOMENTUM";
    private static final String MODEL_DONCHIAN_BREAKOUT = "DONCHIAN_BREAKOUT";
    private static final String MODEL_BOX_BREAKOUT = "BOX_BREAKOUT";
    private static final String MODEL_LEGACY = "LEGACY_TREND_SIGNAL";
    private static final String DEFAULT_ACCOUNT_LABEL = "primary";

    private final CoinStrategyLedgerEventMapper ledgerMapper;
    private final HtxTradeAccountMapper accountMapper;

    public void recordTrendPositionIntent(HtxTrendPositionEntity position) {
        if (position == null || position.getId() == null) {
            return;
        }
        CoinStrategyLedgerEventEntity event = baseEvent(EVENT_POSITION_INTENT,
                "trend-position-intent:" + position.getId(), position);
        event.setReason(clip(position.getEntrySignal(), 1024));
        insertIgnoreDuplicate(event);
    }

    public void recordTrendOrderIntent(HtxTrendOrderEntity order) {
        if (order == null || !StringUtils.hasText(order.getClientOrderId())) {
            return;
        }
        String eventType = HtxTrendRecorder.LEG_CLOSE.equals(order.getLeg()) ? EVENT_CLOSE_ORDER : EVENT_OPEN_ORDER;
        CoinStrategyLedgerEventEntity event = baseEvent(eventType,
                "trend-order:" + safe(order.getLeg()) + ":" + order.getClientOrderId(), order);
        event.setReason(clip(order.getReason(), 1024));
        insertIgnoreDuplicate(event);
    }

    public void recordTrendEntryFill(HtxTrendPositionEntity position) {
        if (position == null || position.getId() == null) {
            return;
        }
        BigDecimal notional = notional(position.getEntryFilledContracts(), position.getContractSize(), position.getEntryAvgPrice());
        CoinStrategyLedgerEventEntity fill = baseEvent(EVENT_OPEN_FILL, "trend-open-fill:" + position.getId(), position);
        fill.setAmountUsdt(notional);
        fill.setReason(clip(position.getEntrySignal(), 1024));
        insertIgnoreDuplicate(fill);
        recordFee(position, EVENT_OPEN_FILL, position.getEntryFee(), position.getEntryClientOrderId(), "entry fee");
    }

    public void recordTrendCloseFill(HtxTrendPositionEntity position) {
        if (position == null || position.getId() == null) {
            return;
        }
        BigDecimal notional = notional(position.getCloseFilledContracts(), position.getContractSize(), position.getCloseAvgPrice());
        CoinStrategyLedgerEventEntity close = baseEvent(EVENT_CLOSE_FILL, "trend-close-fill:" + position.getId(), position);
        close.setAmountUsdt(notional);
        close.setReason(clip(position.getExitReason(), 1024));
        insertIgnoreDuplicate(close);
        recordFee(position, EVENT_CLOSE_FILL, position.getCloseFee(), position.getCloseClientOrderId(), "close fee");
        recordRealizedPnl(position);
    }

    private void recordFee(HtxTrendPositionEntity position, String parentEventType, BigDecimal fee,
                           String clientOrderId, String reason) {
        if (fee == null || fee.signum() == 0) {
            return;
        }
        CoinStrategyLedgerEventEntity event = baseEvent(EVENT_FEE,
                "trend-fee:" + parentEventType + ":" + position.getId(), position);
        event.setClientOrderId(clientOrderId);
        event.setFeeUsdt(fee);
        event.setNetPnlAfterFee(fee.negate());
        event.setReason(reason);
        insertIgnoreDuplicate(event);
    }

    private void recordRealizedPnl(HtxTrendPositionEntity position) {
        CoinStrategyLedgerEventEntity event = baseEvent(EVENT_REALIZED_PNL, "trend-realized-pnl:" + position.getId(), position);
        BigDecimal realizedProfit = nz(position.getRealizedProfit());
        event.setGrossPnlUsdt(realizedProfit);
        event.setNetPnlAfterFee(realizedProfit);
        event.setReason(clip(position.getExitReason(), 1024));
        insertIgnoreDuplicate(event);
    }

    private CoinStrategyLedgerEventEntity baseEvent(String eventType, String eventKey, HtxTrendPositionEntity position) {
        CoinStrategyLedgerEventEntity event = common(eventType, eventKey, label(position.getAccountLabel()),
                position.getContractCode(), position.getId(), null, position.getEntryClientOrderId(), position.getSide(),
                position.getEntrySignal());
        return event;
    }

    private CoinStrategyLedgerEventEntity baseEvent(String eventType, String eventKey, HtxTrendOrderEntity order) {
        return common(eventType, eventKey, label(order.getAccountLabel()), order.getContractCode(), order.getPositionId(),
                order.getId(), order.getClientOrderId(), order.getTrendSide(), order.getReason());
    }

    private CoinStrategyLedgerEventEntity common(String eventType, String eventKey, String accountLabel,
                                                 String contractCode, Long positionId, Long orderId,
                                                 String clientOrderId, String side, String reason) {
        CoinStrategyLedgerEventEntity event = new CoinStrategyLedgerEventEntity();
        event.setEventKey(eventKey);
        event.setEventType(eventType);
        event.setAccountLabel(accountLabel);
        event.setAccountId(accountId(accountLabel));
        event.setExchange(EXCHANGE_HTX);
        event.setMarketType(MARKET_USDT_SWAP);
        event.setStrategyFamily(STRATEGY_FAMILY_TREND);
        event.setStrategyName(STRATEGY_NAME_HTX_TREND);
        event.setModelName(modelName(reason));
        event.setSymbol(symbol(contractCode));
        event.setContractCode(contractCode);
        event.setPositionId(positionId);
        event.setOrderId(orderId);
        event.setClientOrderId(clientOrderId);
        event.setSide(side);
        event.setFeeUsdt(BigDecimal.ZERO);
        event.setFundingFeeUsdt(BigDecimal.ZERO);
        event.setOccurredAt(LocalDateTime.now());
        return event;
    }

    private Long accountId(String accountLabel) {
        try {
            return accountMapper.findIdByAccountLabel(accountLabel);
        } catch (RuntimeException ex) {
            log.warn("strategy ledger account lookup failed account={}: {}", accountLabel,
                    ex.getClass().getSimpleName());
            return null;
        }
    }

    private void insertIgnoreDuplicate(CoinStrategyLedgerEventEntity event) {
        try {
            ledgerMapper.insert(event);
        } catch (DuplicateKeyException ex) {
            log.debug("strategy ledger event already exists key={}", event.getEventKey());
        } catch (RuntimeException ex) {
            log.warn("strategy ledger write failed eventType={} key={} error={}", event.getEventType(),
                    event.getEventKey(), ex.getClass().getSimpleName());
        }
    }

    private static String modelName(String reason) {
        String value = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (value.contains("fib")) {
            return MODEL_FIB_PULLBACK;
        }
        if (value.contains("multi") || value.contains("anchor") || value.contains("ema-stack")
                || value.contains("momentum")) {
            return MODEL_MTF_MOMENTUM;
        }
        if (value.contains("donchian")) {
            return MODEL_DONCHIAN_BREAKOUT;
        }
        if (value.contains("box") || value.contains("breakout")) {
            return MODEL_BOX_BREAKOUT;
        }
        return MODEL_LEGACY;
    }

    private static BigDecimal notional(BigDecimal contracts, BigDecimal contractSize, BigDecimal price) {
        if (contracts == null || contractSize == null || price == null) {
            return null;
        }
        return contracts.multiply(contractSize).multiply(price);
    }

    private static String symbol(String contractCode) {
        if (contractCode == null) {
            return null;
        }
        return contractCode.replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static String label(String accountLabel) {
        return StringUtils.hasText(accountLabel) ? accountLabel.trim() : DEFAULT_ACCOUNT_LABEL;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String clip(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }
}
