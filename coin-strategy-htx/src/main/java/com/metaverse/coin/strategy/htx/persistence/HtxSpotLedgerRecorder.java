package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxInventoryLotEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxSpotFillLedgerEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxInventoryLotMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxSpotFillLedgerMapper;
import com.metaverse.coin.strategy.htx.util.HtxSymbolUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * Append-only ledger for spot fills. HTX spot order queries expose cumulative fields, so the
 * recorder compares each fresh snapshot to the previous durable snapshot and stores only the delta.
 */
@Slf4j
@Service
public class HtxSpotLedgerRecorder {

    public static final String SOURCE_MARKET_MAKER = "MARKET_MAKER";
    public static final String SOURCE_STARTUP_RECOVERY = "STARTUP_RECOVERY";
    public static final String LOT_OPEN = "OPEN";
    public static final String LOT_CLOSED = "CLOSED";

    private final HtxSpotFillLedgerMapper fillMapper;
    private final HtxInventoryLotMapper lotMapper;

    public HtxSpotLedgerRecorder(HtxSpotFillLedgerMapper fillMapper, HtxInventoryLotMapper lotMapper) {
        this.fillMapper = fillMapper;
        this.lotMapper = lotMapper;
    }

    private HtxSpotLedgerRecorder() {
        this.fillMapper = null;
        this.lotMapper = null;
    }

    public static HtxSpotLedgerRecorder noop() {
        return new HtxSpotLedgerRecorder();
    }

    @Transactional
    public LedgerAppendResult recordOrderSnapshot(Long pairId, Long orderRowId, String symbol, String side,
                                                  Long htxOrderId, String clientOrderId, HtxOrder order,
                                                  String source) {
        if (fillMapper == null || lotMapper == null || order == null) {
            return LedgerAppendResult.skipped("ledger unavailable");
        }
        String effectiveSymbol = firstNonBlank(order.getSymbol(), symbol);
        String effectiveSide = normalizeSide(firstNonBlank(side, sideFromType(order.getType())));
        String effectiveClientOrderId = firstNonBlank(order.getClientOrderId(), clientOrderId);
        Long effectiveOrderId = htxOrderId != null ? htxOrderId : order.getOrderId() > 0 ? order.getOrderId() : null;
        if (isBlank(effectiveSymbol) || isBlank(effectiveSide)
                || (effectiveOrderId == null && isBlank(effectiveClientOrderId))) {
            return LedgerAppendResult.skipped("missing order identity");
        }

        try {
            HtxSpotFillLedgerEntity previous = fillMapper.findLatestSnapshot(
                    effectiveSymbol, effectiveSide, effectiveOrderId, effectiveClientOrderId);
            BigDecimal previousAmount = nz(previous == null ? null : previous.getCumulativeFilledAmount());
            BigDecimal previousCash = nz(previous == null ? null : previous.getCumulativeFilledCashAmount());
            BigDecimal previousFees = nz(previous == null ? null : previous.getCumulativeFilledFees());
            BigDecimal currentAmount = current(order.getFilledAmount(), previousAmount);
            BigDecimal currentCash = current(order.getFilledCashAmount(), previousCash);
            BigDecimal currentFees = current(order.getFilledFees(), previousFees);
            if (currentAmount.compareTo(previousAmount) < 0
                    || currentCash.compareTo(previousCash) < 0
                    || currentFees.compareTo(previousFees) < 0) {
                return LedgerAppendResult.skipped("cumulative fill snapshot regressed");
            }

            BigDecimal deltaBase = currentAmount.subtract(previousAmount);
            BigDecimal deltaCash = currentCash.subtract(previousCash);
            BigDecimal deltaFee = currentFees.subtract(previousFees);
            if (deltaBase.signum() <= 0 && deltaCash.signum() <= 0 && deltaFee.signum() <= 0) {
                return LedgerAppendResult.duplicated();
            }

            String feeCurrency = "BUY".equals(effectiveSide)
                    ? HtxSymbolUtil.base(effectiveSymbol) : HtxSymbolUtil.quote(effectiveSymbol);
            HtxSpotFillLedgerEntity entry = new HtxSpotFillLedgerEntity();
            entry.setLedgerKey(ledgerKey(effectiveSymbol, effectiveSide, effectiveOrderId,
                    effectiveClientOrderId, currentAmount, currentCash, currentFees));
            entry.setSymbol(effectiveSymbol);
            entry.setPairId(pairId);
            entry.setOrderRowId(orderRowId);
            entry.setHtxOrderId(effectiveOrderId);
            entry.setClientOrderId(effectiveClientOrderId);
            entry.setSide(effectiveSide);
            entry.setOrderState(order.getState());
            entry.setDeltaBase("BUY".equals(effectiveSide) ? deltaBase : deltaBase.negate());
            entry.setDeltaQuote("BUY".equals(effectiveSide) ? deltaCash.negate() : deltaCash.subtract(deltaFee));
            entry.setFeeAmount(deltaFee);
            entry.setFeeCurrency(feeCurrency);
            entry.setCumulativeFilledAmount(currentAmount);
            entry.setCumulativeFilledCashAmount(currentCash);
            entry.setCumulativeFilledFees(currentFees);
            entry.setSource(isBlank(source) ? "UNKNOWN" : source);
            entry.setObservedAt(LocalDateTime.now());
            fillMapper.insert(entry);

            BigDecimal unmatchedSellBase = applyInventory(entry, "BUY".equals(effectiveSide)
                    && feeCurrency.equalsIgnoreCase(HtxSymbolUtil.base(effectiveSymbol)));
            return new LedgerAppendResult(true, false, null, deltaBase, entry.getDeltaQuote(), unmatchedSellBase);
        } catch (DuplicateKeyException duplicate) {
            return LedgerAppendResult.duplicated();
        } catch (Exception ex) {
            log.error("spot fill ledger append failed pairId={} orderId={} side={}",
                    pairId, effectiveOrderId, effectiveSide, ex);
            throw new HtxPersistenceException("spot fill ledger append failed", ex);
        }
    }

    public BigDecimal openInventory(String symbol) {
        if (lotMapper == null || isBlank(symbol)) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = lotMapper.sumOpenRemainingBase(symbol);
        return total == null ? BigDecimal.ZERO : total;
    }

    private BigDecimal applyInventory(HtxSpotFillLedgerEntity entry, boolean feeInBase) {
        if ("BUY".equals(entry.getSide())) {
            BigDecimal feeBase = feeInBase ? nz(entry.getFeeAmount()) : BigDecimal.ZERO;
            BigDecimal acquired = nz(entry.getDeltaBase()).subtract(feeBase).max(BigDecimal.ZERO);
            if (acquired.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            HtxInventoryLotEntity lot = new HtxInventoryLotEntity();
            lot.setSymbol(entry.getSymbol());
            lot.setSourcePairId(entry.getPairId());
            lot.setBuyOrderId(entry.getHtxOrderId());
            lot.setBuyFillLedgerId(entry.getId());
            lot.setAcquiredBase(acquired);
            lot.setRemainingBase(acquired);
            lot.setCostQuote(nz(entry.getDeltaQuote()).abs());
            lot.setFeeBase(feeBase);
            lot.setStatus(LOT_OPEN);
            lot.setUpdatedAt(LocalDateTime.now());
            lotMapper.insert(lot);
            return BigDecimal.ZERO;
        }
        if (!"SELL".equals(entry.getSide())) {
            return BigDecimal.ZERO;
        }
        BigDecimal toConsume = nz(entry.getDeltaBase()).abs();
        List<HtxInventoryLotEntity> lots = lotMapper.findOpenLots(entry.getSymbol());
        if (lots == null) {
            return toConsume;
        }
        for (HtxInventoryLotEntity lot : lots) {
            if (toConsume.signum() <= 0) {
                break;
            }
            BigDecimal remaining = nz(lot.getRemainingBase());
            if (remaining.signum() <= 0) {
                continue;
            }
            BigDecimal consume = remaining.min(toConsume);
            BigDecimal next = remaining.subtract(consume);
            HtxInventoryLotEntity update = new HtxInventoryLotEntity();
            update.setId(lot.getId());
            update.setRemainingBase(next);
            update.setStatus(next.signum() <= 0 ? LOT_CLOSED : LOT_OPEN);
            update.setUpdatedAt(LocalDateTime.now());
            lotMapper.updateById(update);
            toConsume = toConsume.subtract(consume);
        }
        if (toConsume.signum() > 0) {
            log.warn("spot fill ledger saw unmatched sell inventory symbol={} pairId={} unmatched={}",
                    entry.getSymbol(), entry.getPairId(), toConsume);
        }
        return toConsume;
    }

    private static BigDecimal current(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String sideFromType(String type) {
        if (type == null) {
            return null;
        }
        String lower = type.toLowerCase();
        if (lower.startsWith("buy")) {
            return "BUY";
        }
        if (lower.startsWith("sell")) {
            return "SELL";
        }
        return null;
    }

    private static String normalizeSide(String side) {
        if (side == null) {
            return null;
        }
        String lower = side.toLowerCase();
        if (lower.startsWith("buy")) {
            return "BUY";
        }
        if (lower.startsWith("sell")) {
            return "SELL";
        }
        return null;
    }

    private static String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first : second;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String ledgerKey(String symbol, String side, Long orderId, String clientOrderId,
                                    BigDecimal cumulativeAmount, BigDecimal cumulativeCash,
                                    BigDecimal cumulativeFees) {
        String source = symbol + "|" + side + "|" + (orderId == null ? "" : orderId)
                + "|" + (clientOrderId == null ? "" : clientOrderId)
                + "|" + normalized(cumulativeAmount)
                + "|" + normalized(cumulativeCash)
                + "|" + normalized(cumulativeFees);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "spotfill:" + HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String normalized(BigDecimal value) {
        return nz(value).stripTrailingZeros().toPlainString();
    }

    public record LedgerAppendResult(boolean appended, boolean duplicate, String skippedReason,
                                     BigDecimal deltaBase, BigDecimal deltaQuote,
                                     BigDecimal unmatchedSellBase) {
        static LedgerAppendResult duplicated() {
            return new LedgerAppendResult(false, true, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        static LedgerAppendResult skipped(String reason) {
            return new LedgerAppendResult(false, false, reason,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }
}
