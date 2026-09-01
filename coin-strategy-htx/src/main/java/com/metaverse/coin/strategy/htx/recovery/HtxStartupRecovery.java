package com.metaverse.coin.strategy.htx.recovery;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.HtxSpotLedgerRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxOrderMapper;
import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.htx.util.HtxSymbolUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 崩溃恢复：启动后扫描 write-ahead 未确认发出的订单意图，据 HTX 实况回填或标记失败。
 * 仅实盘（trade.enabled=true）执行。
 * <p>
 * 仅当 {@link HtxApiService} bean 存在（AK/SK 已配置）时装配，与 {@code HtxTradingService} 同模式；
 * 无凭证时（如集成测试上下文）不创建本 bean，避免上下文加载失败。
 */
@Slf4j
@Component
@ConditionalOnBean(HtxApiService.class)
public class HtxStartupRecovery {

    private static final int STARTUP_RECONCILE_LIMIT = 500;

    private final HtxOrderMapper orderMapper;
    private final HtxApiService htxApiService;
    private final HtxTradeProperties props;
    private final HtxSpotLedgerRecorder ledgerRecorder;

    public HtxStartupRecovery(HtxOrderMapper orderMapper, HtxApiService htxApiService, HtxTradeProperties props) {
        this(orderMapper, htxApiService, props, HtxSpotLedgerRecorder.noop());
    }

    @Autowired
    public HtxStartupRecovery(HtxOrderMapper orderMapper, HtxApiService htxApiService,
                              HtxTradeProperties props, HtxSpotLedgerRecorder ledgerRecorder) {
        this.orderMapper = orderMapper;
        this.htxApiService = htxApiService;
        this.props = props;
        this.ledgerRecorder = ledgerRecorder;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        recover();
    }

    public void recover() {
        if (!props.getTrade().isEnabled()) {
            log.info("HTX startup recovery skipped (trade disabled / dry-run)");
            return;
        }
        List<HtxOrderEntity> pending = findPendingSendOrders();
        if (pending == null || pending.isEmpty()) {
            log.info("HTX startup recovery: no PENDING_SEND orders");
        } else {
            log.warn("HTX startup recovery: {} PENDING_SEND order(s) to reconcile", pending.size());
            for (HtxOrderEntity e : pending) {
                reconcilePendingSend(e);
            }
        }

        List<HtxOrderEntity> submitted = findSubmittedSpotOrders();
        if (submitted == null || submitted.isEmpty()) {
            log.info("HTX startup recovery: no submitted spot orders to snapshot");
            return;
        }
        int reconciled = 0;
        for (HtxOrderEntity e : submitted) {
            if (!isSubmittedSpotCandidate(e)) {
                continue;
            }
            if (reconcileSubmittedSpotOrder(e)) {
                reconciled++;
            }
        }
        if (reconciled > 0) {
            log.warn("HTX startup recovery: {} submitted spot order(s) snapshotted", reconciled);
        }
    }

    private List<HtxOrderEntity> findPendingSendOrders() {
        QueryWrapper<HtxOrderEntity> qw = new QueryWrapper<>();
        qw.eq("intent_status", HtxStatus.INTENT_PENDING_SEND);
        return orderMapper.selectList(qw);
    }

    private List<HtxOrderEntity> findSubmittedSpotOrders() {
        QueryWrapper<HtxOrderEntity> qw = new QueryWrapper<>();
        qw.in("intent_status", HtxStatus.INTENT_SENT, HtxStatus.INTENT_TERMINAL)
                .in("side", "buy", "sell", "BUY", "SELL")
                .orderByDesc("id")
                .last("LIMIT " + STARTUP_RECONCILE_LIMIT);
        return orderMapper.selectList(qw);
    }

    private void reconcilePendingSend(HtxOrderEntity e) {
        try {
            HtxRestResponse<HtxOrder> resp = queryExchangeOrder(e);
            if (hasOrder(resp)) {
                HtxOrder order = resp.getData();
                HtxOrderEntity upd = snapshotUpdate(e, order);
                upd.setIntentStatus(HtxStatus.INTENT_SENT);
                recordLedgerSnapshot(e, order);
                log.warn("recovery: order id={} clientId={} was actually SENT (htxId={})",
                        e.getId(), e.getClientOrderId(), order.getOrderId());
                orderMapper.updateById(upd);
            } else {
                HtxOrderEntity upd = new HtxOrderEntity();
                upd.setId(e.getId());
                upd.setIntentStatus(HtxStatus.INTENT_SEND_FAILED);
                log.warn("recovery: order id={} clientId={} confirmed SEND_FAILED",
                        e.getId(), e.getClientOrderId());
                orderMapper.updateById(upd);
            }
        } catch (RuntimeException ex) {
            log.error("recovery failed for order id={} clientId={}: {}",
                    e.getId(), e.getClientOrderId(), ex.getMessage());
        }
    }

    private boolean reconcileSubmittedSpotOrder(HtxOrderEntity e) {
        try {
            HtxRestResponse<HtxOrder> resp = queryExchangeOrder(e);
            if (!hasOrder(resp)) {
                log.warn("startup snapshot skipped: exchange has no order rowId={} htxId={} clientId={}",
                        e.getId(), e.getHtxOrderId(), e.getClientOrderId());
                return false;
            }
            HtxOrder order = resp.getData();
            orderMapper.updateById(snapshotUpdate(e, order));
            recordLedgerSnapshot(e, order);
            return true;
        } catch (RuntimeException ex) {
            log.error("startup snapshot failed for order id={} clientId={}: {}",
                    e.getId(), e.getClientOrderId(), ex.getMessage());
            return false;
        }
    }

    private HtxRestResponse<HtxOrder> queryExchangeOrder(HtxOrderEntity e) {
        if (e.getHtxOrderId() != null) {
            return htxApiService.getSpotService().queryOrder(e.getHtxOrderId());
        }
        return htxApiService.getSpotService().queryOrderByClientId(e.getClientOrderId());
    }

    private HtxOrderEntity snapshotUpdate(HtxOrderEntity e, HtxOrder order) {
        HtxOrderEntity upd = new HtxOrderEntity();
        upd.setId(e.getId());
        if (order.getOrderId() > 0) {
            upd.setHtxOrderId(order.getOrderId());
        }
        upd.setState(order.getState());
        upd.setFilledAmount(order.getFilledAmount());
        upd.setFilledCashAmount(order.getFilledCashAmount());
        upd.setFilledFees(order.getFilledFees());
        upd.setFeeCurrency(feeCurrency(e, order));
        upd.setIntentStatus(orderIntentStatus(order.getState()));
        upd.setUpdatedAt(LocalDateTime.now());
        return upd;
    }

    private String feeCurrency(HtxOrderEntity e, HtxOrder order) {
        String symbol = order.getSymbol();
        String side = normalizeSide(firstNonBlank(e.getSide(), sideFromType(order.getType())));
        return "BUY".equals(side) ? HtxSymbolUtil.base(symbol) : HtxSymbolUtil.quote(symbol);
    }

    private void recordLedgerSnapshot(HtxOrderEntity e, HtxOrder order) {
        try {
            ledgerRecorder.recordOrderSnapshot(e.getPairId(), e.getId(), order.getSymbol(),
                    e.getSide(), order.getOrderId(), e.getClientOrderId(), order,
                    HtxSpotLedgerRecorder.SOURCE_STARTUP_RECOVERY);
        } catch (RuntimeException ex) {
            log.error("startup recovery ledger snapshot failed order id={} clientId={}; recovery continues",
                    e.getId(), e.getClientOrderId(), ex);
        }
    }

    private boolean isSubmittedSpotCandidate(HtxOrderEntity e) {
        if (e == null || !isSubmittedIntent(e.getIntentStatus())) {
            return false;
        }
        if (e.getHtxOrderId() == null && isBlank(e.getClientOrderId())) {
            return false;
        }
        return normalizeSide(e.getSide()) != null;
    }

    private static boolean hasOrder(HtxRestResponse<HtxOrder> resp) {
        return resp != null && resp.ok() && resp.getData() != null;
    }

    private static boolean isSubmittedIntent(String intentStatus) {
        return HtxStatus.INTENT_SENT.equals(intentStatus)
                || HtxStatus.INTENT_TERMINAL.equals(intentStatus);
    }

    private static String orderIntentStatus(String state) {
        return isOrderTerminal(state) ? HtxStatus.INTENT_TERMINAL : HtxStatus.INTENT_SENT;
    }

    private static boolean isOrderTerminal(String state) {
        return "filled".equalsIgnoreCase(state)
                || "canceled".equalsIgnoreCase(state)
                || "partial-canceled".equalsIgnoreCase(state)
                || "rejected".equalsIgnoreCase(state);
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
}
