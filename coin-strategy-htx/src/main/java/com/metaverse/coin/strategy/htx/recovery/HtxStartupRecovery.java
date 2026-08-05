package com.metaverse.coin.strategy.htx.recovery;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxOrderMapper;
import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 崩溃恢复：启动后扫描 write-ahead 未确认发出的订单意图，据 HTX 实况回填或标记失败。
 * 仅实盘（trade.enabled=true）执行。
 */
@Slf4j
@Component
public class HtxStartupRecovery {

    private final HtxOrderMapper orderMapper;
    private final HtxApiService htxApiService;
    private final HtxTradeProperties props;

    public HtxStartupRecovery(HtxOrderMapper orderMapper, HtxApiService htxApiService, HtxTradeProperties props) {
        this.orderMapper = orderMapper;
        this.htxApiService = htxApiService;
        this.props = props;
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
        QueryWrapper<HtxOrderEntity> qw = new QueryWrapper<>();
        qw.eq("intent_status", HtxStatus.INTENT_PENDING_SEND);
        List<HtxOrderEntity> pending = orderMapper.selectList(qw);
        if (pending == null || pending.isEmpty()) {
            log.info("HTX startup recovery: no PENDING_SEND orders");
            return;
        }
        log.warn("HTX startup recovery: {} PENDING_SEND order(s) to reconcile", pending.size());
        for (HtxOrderEntity e : pending) {
            reconcileOne(e);
        }
    }

    private void reconcileOne(HtxOrderEntity e) {
        try {
            HtxRestResponse<HtxOrder> resp = htxApiService.getSpotService().queryOrderByClientId(e.getClientOrderId());
            HtxOrderEntity upd = new HtxOrderEntity();
            upd.setId(e.getId());
            if (resp != null && resp.ok() && resp.getData() != null) {
                upd.setHtxOrderId(resp.getData().getOrderId());
                upd.setState(resp.getData().getState());
                upd.setIntentStatus(HtxStatus.INTENT_SENT);
                log.warn("recovery: order id={} clientId={} was actually SENT (htxId={})",
                        e.getId(), e.getClientOrderId(), resp.getData().getOrderId());
            } else {
                upd.setIntentStatus(HtxStatus.INTENT_SEND_FAILED);
                log.warn("recovery: order id={} clientId={} confirmed SEND_FAILED",
                        e.getId(), e.getClientOrderId());
            }
            orderMapper.updateById(upd);
        } catch (RuntimeException ex) {
            log.error("recovery failed for order id={} clientId={}: {}",
                    e.getId(), e.getClientOrderId(), ex.getMessage());
        }
    }
}
