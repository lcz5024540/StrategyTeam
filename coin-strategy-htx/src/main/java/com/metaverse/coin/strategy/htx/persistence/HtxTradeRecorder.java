package com.metaverse.coin.strategy.htx.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.htx.persistence.entity.*;
import com.metaverse.coin.strategy.htx.persistence.mapper.*;
import com.metaverse.coin.strategy.htx.service.HtxLockedPairRequest;
import com.metaverse.coin.strategy.htx.util.HtxSymbolUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 本模块 DB 唯一入口。写前方法失败抛 {@link HtxPersistenceException}（fail-closed）；
 * 更新/记录方法吞异常仅告警（真实订单已发，无法回滚，交由启动恢复/对账器补偿）。
 * 单条 insert/update 走 autocommit（不加 @Transactional），确保发 REST 前已持久化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HtxTradeRecorder {

    private final HtxLockedPairMapper pairMapper;
    private final HtxOrderMapper orderMapper;
    private final HtxCancelMapper cancelMapper;
    private final HtxExposureMapper exposureMapper;

    // ---- 写前（fail-closed） ----

    public long createPair(HtxLockedPairRequest req, boolean dryRun) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setClientOrderId(req.getClientOrderId());
            e.setSymbol(req.getSymbol());
            e.setReqQuantity(req.getQuantity());
            e.setBuyPrice(req.getBuyPrice());
            e.setSellPrice(req.getSellPrice());
            e.setStatus(HtxStatus.NEW);
            e.setDryRun(dryRun);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.insert(e);
            return e.getId();
        } catch (Exception ex) {
            throw new HtxPersistenceException("createPair failed", ex);
        }
    }

    public long insertOrderIntent(long pairId, String clientOrderId, String side,
                                  String type, BigDecimal price, BigDecimal reqAmount) {
        try {
            HtxOrderEntity e = new HtxOrderEntity();
            e.setPairId(pairId);
            e.setClientOrderId(clientOrderId);
            e.setSide(side);
            e.setType(type);
            e.setPrice(price);
            e.setReqAmount(reqAmount);
            e.setIntentStatus(HtxStatus.INTENT_PENDING_SEND);
            e.setUpdatedAt(LocalDateTime.now());
            orderMapper.insert(e);
            return e.getId();
        } catch (Exception ex) {
            throw new HtxPersistenceException("insertOrderIntent failed", ex);
        }
    }

    // ---- 更新（best-effort，仅告警） ----

    public void markOrderSent(long orderRowId, Long htxOrderId) {
        try {
            HtxOrderEntity e = new HtxOrderEntity();
            e.setId(orderRowId);
            e.setHtxOrderId(htxOrderId);
            e.setIntentStatus(HtxStatus.INTENT_SENT);
            e.setUpdatedAt(LocalDateTime.now());
            orderMapper.updateById(e);
        } catch (Exception ex) {
            log.error("markOrderSent failed rowId={} htxId={}", orderRowId, htxOrderId, ex);
        }
    }

    public void markOrderSendFailed(long orderRowId, String msg) {
        try {
            HtxOrderEntity e = new HtxOrderEntity();
            e.setId(orderRowId);
            e.setIntentStatus(HtxStatus.INTENT_SEND_FAILED);
            e.setState(msg);
            e.setUpdatedAt(LocalDateTime.now());
            orderMapper.updateById(e);
        } catch (Exception ex) {
            log.error("markOrderSendFailed failed rowId={}", orderRowId, ex);
        }
    }

    public void updateOrderFill(long orderRowId, String symbol, String side, HtxOrder o, String rawJson) {
        try {
            HtxOrderEntity e = new HtxOrderEntity();
            e.setId(orderRowId);
            e.setState(o.getState());
            e.setFilledAmount(o.getFilledAmount());
            e.setFilledCashAmount(o.getFilledCashAmount());
            e.setFilledFees(o.getFilledFees());
            e.setFeeCurrency("buy".equalsIgnoreCase(side)
                    ? HtxSymbolUtil.base(symbol) : HtxSymbolUtil.quote(symbol));
            e.setRawResponse(rawJson);
            if (isTerminal(o.getState())) {
                e.setIntentStatus(HtxStatus.INTENT_TERMINAL);
            }
            e.setUpdatedAt(LocalDateTime.now());
            orderMapper.updateById(e);
        } catch (Exception ex) {
            log.error("updateOrderFill failed rowId={}", orderRowId, ex);
        }
    }

    public void updatePairStatus(long pairId, String status, String resultCode, String message) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setId(pairId);
            e.setStatus(status);
            e.setResultCode(resultCode);
            e.setMessage(message);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.updateById(e);
        } catch (Exception ex) {
            log.error("updatePairStatus failed pairId={} status={}", pairId, status, ex);
        }
    }

    public void updatePairBuy(long pairId, Long buyOrderId, BigDecimal buyFilled) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setId(pairId);
            e.setBuyOrderId(buyOrderId);
            e.setBuyFilledAmount(buyFilled);
            e.setStatus(HtxStatus.BUY_FILLED);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.updateById(e);
        } catch (Exception ex) {
            log.error("updatePairBuy failed pairId={}", pairId, ex);
        }
    }

    public void updatePairSell(long pairId, Long sellOrderId, BigDecimal sellFilled) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setId(pairId);
            e.setSellOrderId(sellOrderId);
            e.setSellFilledAmount(sellFilled);
            e.setStatus(HtxStatus.SELL_SENT);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.updateById(e);
        } catch (Exception ex) {
            log.error("updatePairSell failed pairId={}", pairId, ex);
        }
    }

    public void finalizePair(long pairId, BigDecimal netBaseDelta, BigDecimal totalFeeBase,
                             BigDecimal totalFeeQuote, BigDecimal netQuoteCost,
                             String resultCode, String message) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setId(pairId);
            e.setStatus(HtxStatus.DONE);
            e.setNetBaseDelta(netBaseDelta);
            e.setTotalFeeBase(totalFeeBase);
            e.setTotalFeeQuote(totalFeeQuote);
            e.setNetQuoteCost(netQuoteCost);
            e.setResultCode(resultCode);
            e.setMessage(message);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.updateById(e);
        } catch (Exception ex) {
            log.error("finalizePair failed pairId={}", pairId, ex);
        }
    }

    // ---- 做市（market-maker）专用 ----

    /**
     * 写前建 cell（fail-closed）：status=MM_RESTING_BUY，strategy=MARKET_MAKER，dry_run=0。
     * clientOrderId 幂等键。返回生成主键 id。
     */
    public long createMmCell(String symbol, BigDecimal qty, BigDecimal buyPrice,
                             BigDecimal sellPrice, String clientId) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setClientOrderId(clientId);
            e.setSymbol(symbol);
            e.setReqQuantity(qty);
            e.setBuyPrice(buyPrice);
            e.setSellPrice(sellPrice);
            e.setStatus(HtxStatus.MM_RESTING_BUY);
            e.setStrategy(HtxStatus.STRATEGY_MARKET_MAKER);
            e.setDryRun(false);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.insert(e);
            return e.getId();
        } catch (Exception ex) {
            throw new HtxPersistenceException("createMmCell failed", ex);
        }
    }

    /** 买单已发，回填 buyOrderId（best-effort）。 */
    public void markMmBuySent(long pairId, Long buyOrderId) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setId(pairId);
            e.setBuyOrderId(buyOrderId);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.updateById(e);
        } catch (Exception ex) {
            log.error("markMmBuySent failed pairId={}", pairId, ex);
        }
    }

    /** 买腿成交、卖单已挂：→ MM_RESTING_SELL，记 sellOrderId 与买腿成交量（best-effort）。 */
    public void markMmSellResting(long pairId, Long sellOrderId, BigDecimal buyFilled) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setId(pairId);
            e.setSellOrderId(sellOrderId);
            e.setBuyFilledAmount(buyFilled);
            e.setStatus(HtxStatus.MM_RESTING_SELL);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.updateById(e);
        } catch (Exception ex) {
            log.error("markMmSellResting failed pairId={}", pairId, ex);
        }
    }

    /** 卖腿成交、round-trip 完成：→ MM_DONE（best-effort）。 */
    public void markMmDone(long pairId, BigDecimal sellFilled) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setId(pairId);
            e.setSellFilledAmount(sellFilled);
            e.setStatus(HtxStatus.MM_DONE);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.updateById(e);
        } catch (Exception ex) {
            log.error("markMmDone failed pairId={}", pairId, ex);
        }
    }

    /** 买单超 TTL 已撤、cell 关闭：→ MM_CANCELLED_BUY（best-effort）。 */
    public void markMmCancelledBuy(long pairId) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setId(pairId);
            e.setStatus(HtxStatus.MM_CANCELLED_BUY);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.updateById(e);
        } catch (Exception ex) {
            log.error("markMmCancelledBuy failed pairId={}", pairId, ex);
        }
    }

    /** 某标的的 open cell（RESTING_BUY / RESTING_SELL）。 */
    public List<HtxLockedPairEntity> findOpenMmCells(String symbol) {
        return pairMapper.selectList(new QueryWrapper<HtxLockedPairEntity>()
                .eq("strategy", HtxStatus.STRATEGY_MARKET_MAKER)
                .eq("symbol", symbol)
                .in("status", HtxStatus.MM_RESTING_BUY, HtxStatus.MM_RESTING_SELL));
    }

    /** 全部标的的 open cell（用于重启恢复）。 */
    public List<HtxLockedPairEntity> findAllOpenMmCells() {
        return pairMapper.selectList(new QueryWrapper<HtxLockedPairEntity>()
                .eq("strategy", HtxStatus.STRATEGY_MARKET_MAKER)
                .in("status", HtxStatus.MM_RESTING_BUY, HtxStatus.MM_RESTING_SELL));
    }

    /** 某标的 open cell 数（开仓 K 闸用）。 */
    public int countOpenMmCells(String symbol) {
        Long n = pairMapper.selectCount(new QueryWrapper<HtxLockedPairEntity>()
                .eq("strategy", HtxStatus.STRATEGY_MARKET_MAKER)
                .eq("symbol", symbol)
                .in("status", HtxStatus.MM_RESTING_BUY, HtxStatus.MM_RESTING_SELL));
        return n == null ? 0 : n.intValue();
    }

    public void recordCancel(long orderRowId, Long htxOrderId, String reason,
                             Boolean ok, String postState, BigDecimal postFilled) {        try {
            HtxCancelEntity e = new HtxCancelEntity();
            e.setOrderId(orderRowId);
            e.setHtxOrderId(htxOrderId);
            e.setReason(reason);
            e.setCancelOk(ok);
            e.setPostCancelState(postState);
            e.setPostCancelFilled(postFilled);
            cancelMapper.insert(e);
        } catch (Exception ex) {
            log.error("recordCancel failed orderRowId={}", orderRowId, ex);
        }
    }

    public long recordExposure(long pairId, String symbol, String exposedSide,
                               BigDecimal amount, String detail) {
        try {
            HtxExposureEntity e = new HtxExposureEntity();
            e.setPairId(pairId);
            e.setSymbol(symbol);
            e.setExposedSide(exposedSide);
            e.setExposedAmount(amount);
            e.setStatus(HtxStatus.EXP_OPEN);
            e.setDetail(detail);
            e.setUpdatedAt(LocalDateTime.now());
            exposureMapper.insert(e);
            return e.getId();
        } catch (Exception ex) {
            log.error("recordExposure failed pairId={}", pairId, ex);
            return -1L;
        }
    }

    // ---- 查询（恢复 / 对账器） ----

    /**
     * 当日累计实盘买腿名义之和（USDT），用于每日交易额上限风控。
     * 口径：非 dry-run、created_at 落在当日（系统默认时区起始）的对，其 req_quantity × buy_price 之和。
     * <p>
     * 属于下单前的风控读取：读失败即抛 {@link HtxPersistenceException}，由上层 fail-closed 拒单——
     * 无法确认是否超限时，宁可拒单也不冒超额风险。
     */
    public BigDecimal sumTodayLiveQuoteNotional() {
        try {
            LocalDateTime start = LocalDate.now().atStartOfDay();
            BigDecimal sum = pairMapper.sumLiveQuoteNotionalSince(start);
            return sum == null ? BigDecimal.ZERO : sum;
        } catch (Exception ex) {
            throw new HtxPersistenceException("sumTodayLiveQuoteNotional failed", ex);
        }
    }

    public List<HtxLockedPairEntity> findNonTerminalPairs() {
        return pairMapper.selectList(new QueryWrapper<HtxLockedPairEntity>()
                .in("status", HtxStatus.BUY_SENT, HtxStatus.BUY_FILLED,
                        HtxStatus.SELL_SENT, HtxStatus.SELL_PENDING, HtxStatus.EXPOSED));
    }

    public List<HtxOrderEntity> findOrdersByPair(long pairId) {
        return orderMapper.selectList(new QueryWrapper<HtxOrderEntity>().eq("pair_id", pairId));
    }

    public List<HtxExposureEntity> findOpenExposures() {
        return exposureMapper.selectList(new QueryWrapper<HtxExposureEntity>()
                .eq("status", HtxStatus.EXP_OPEN));
    }

    public void markExposureFlattening(long id) {
        updateExposureStatus(id, HtxStatus.EXP_FLATTENING, null, null);
    }

    public void markExposureFlattened(long id, Long flattenOrderId) {
        updateExposureStatus(id, HtxStatus.EXP_FLATTENED, flattenOrderId, null);
    }

    public void markExposureManual(long id, String detail) {
        updateExposureStatus(id, HtxStatus.EXP_MANUAL, null, detail);
    }

    /**
     * 对账器平仓单 write-ahead：先落一行 sell-market 意图订单（best-effort，
     * 失败仅告警不抛——平仓由重试兜底，不因记账失败阻断收敛）。返回订单行 id（失败 -1）。
     */
    public long insertFlattenIntent(long pairId, String symbol, BigDecimal amount) {
        try {
            HtxOrderEntity e = new HtxOrderEntity();
            e.setPairId(pairId);
            e.setClientOrderId("flatten-" + pairId + "-" + System.currentTimeMillis());
            e.setSide("sell");
            e.setType("sell-market");
            e.setReqAmount(amount);
            e.setIntentStatus(HtxStatus.INTENT_PENDING_SEND);
            orderMapper.insert(e);
            return e.getId();
        } catch (Exception ex) {
            log.error("insertFlattenIntent failed pairId={}", pairId, ex);
            return -1L;
        }
    }

    /** 平仓失败重试计数 +1（best-effort）。 */
    public void incrementFlattenRetry(long id) {
        try {
            HtxExposureEntity cur = exposureMapper.selectById(id);
            int next = (cur == null || cur.getFlattenRetries() == null) ? 1 : cur.getFlattenRetries() + 1;
            HtxExposureEntity e = new HtxExposureEntity();
            e.setId(id);
            e.setFlattenRetries(next);
            e.setUpdatedAt(LocalDateTime.now());
            exposureMapper.updateById(e);
        } catch (Exception ex) {
            log.error("incrementFlattenRetry failed id={}", id, ex);
        }
    }

    private void updateExposureStatus(long id, String status, Long flattenOrderId, String detail) {
        try {
            HtxExposureEntity e = new HtxExposureEntity();
            e.setId(id);
            e.setStatus(status);
            e.setFlattenOrderId(flattenOrderId);
            e.setDetail(detail);
            e.setUpdatedAt(LocalDateTime.now());
            exposureMapper.updateById(e);
        } catch (Exception ex) {
            log.error("updateExposureStatus failed id={} status={}", id, status, ex);
        }
    }

    private static boolean isTerminal(String state) {
        return "filled".equalsIgnoreCase(state) || "canceled".equalsIgnoreCase(state)
                || "partial-canceled".equalsIgnoreCase(state) || "rejected".equalsIgnoreCase(state);
    }
}
