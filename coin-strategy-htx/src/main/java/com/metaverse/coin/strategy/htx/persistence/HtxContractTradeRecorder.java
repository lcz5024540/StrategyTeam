package com.metaverse.coin.strategy.htx.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxContractLegEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxContractCycleEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxContractLegMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxContractCycleMapper;
import com.metaverse.coin.strategy.htx.service.HtxContractCycleRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Write-ahead persistence for leveraged contract cycles. */
@Slf4j
@Service
@RequiredArgsConstructor
public class HtxContractTradeRecorder {

    public static final String INTENT = "INTENT";
    public static final String OPEN_SENT = "OPEN_SENT";
    public static final String OPEN_FILLED = "OPEN_FILLED";
    public static final String OPEN_UNKNOWN = "OPEN_UNKNOWN";
    public static final String OPEN_CANCELLED = "OPEN_CANCELLED";
    public static final String OPEN_REJECTED = "OPEN_REJECTED";
    public static final String CLOSE_PENDING = "CLOSE_PENDING";
    public static final String EXPOSURE = "EXPOSURE";
    public static final String DONE = "DONE";
    public static final String DRY_RUN_DONE = "DRY_RUN_DONE";

    public static final String LONG_OPEN = "LONG_OPEN";
    public static final String SHORT_OPEN = "SHORT_OPEN";
    public static final String LONG_CLOSE = "LONG_CLOSE";
    public static final String SHORT_CLOSE = "SHORT_CLOSE";
    public static final String LEG_INTENT = "INTENT";

    private static final List<String> ACTIVE_STATUSES = List.of(
            INTENT, OPEN_SENT, OPEN_FILLED, OPEN_UNKNOWN, CLOSE_PENDING, EXPOSURE);

    private final HtxContractCycleMapper mapper;
    private final HtxContractLegMapper legMapper;

    /** A stale or exposed cycle blocks another cycle for the same contract until reconciled. */
    public boolean hasActiveCycle(String contractCode) {
        try {
            Long count = mapper.selectCount(new QueryWrapper<HtxContractCycleEntity>()
                    .eq("contract_code", contractCode)
                    .in("status", ACTIVE_STATUSES));
            return count != null && count > 0;
        } catch (Exception e) {
            throw new HtxPersistenceException("contract active-cycle query failed", e);
        }
    }

    /** Insert must commit before the first live open instruction is sent. */
    public long createIntent(HtxContractCycleRequest request, String contractCode, int leverage) {
        try {
            HtxContractCycleEntity entity = new HtxContractCycleEntity();
            entity.setClientOrderId(request.getClientOrderId());
            entity.setContractCode(contractCode);
            entity.setReqVolume(request.getVolume());
            entity.setOpenPrice(request.getOpenPrice());
            entity.setClosePrice(request.getClosePrice());
            entity.setLeverage(leverage);
            entity.setStatus(INTENT);
            entity.setUpdatedAt(LocalDateTime.now());
            mapper.insert(entity);
            return entity.getId();
        } catch (Exception e) {
            throw new HtxPersistenceException("contract intent insert failed", e);
        }
    }

    /** Each leg intent must be durable before its containing batch request is sent. */
    public void createLegIntent(long cycleId, String legType, long clientOrderId,
                                String side, String positionSide, boolean reduceOnly,
                                long volume, BigDecimal price) {
        try {
            HtxContractLegEntity entity = new HtxContractLegEntity();
            entity.setCycleId(cycleId);
            entity.setLegType(legType);
            entity.setClientOrderId(clientOrderId);
            entity.setSide(side);
            entity.setPositionSide(positionSide);
            entity.setReduceOnly(reduceOnly);
            entity.setReqVolume(volume);
            entity.setPrice(price);
            entity.setState(LEG_INTENT);
            entity.setFilledVolume(BigDecimal.ZERO);
            entity.setFee(BigDecimal.ZERO);
            entity.setRealizedProfit(BigDecimal.ZERO);
            entity.setUpdatedAt(LocalDateTime.now());
            legMapper.insert(entity);
        } catch (Exception e) {
            throw new HtxPersistenceException("contract leg intent insert failed: " + legType, e);
        }
    }

    /** Post-send leg updates are best effort; an unchanged INTENT remains visible for reconciliation. */
    public void updateLeg(long cycleId, String legType, Long orderId, String state,
                          BigDecimal filledVolume, BigDecimal averagePrice, BigDecimal fee,
                          String feeCurrency, BigDecimal realizedProfit, String message) {
        try {
            HtxContractLegEntity entity = new HtxContractLegEntity();
            entity.setOrderId(orderId);
            entity.setState(state);
            entity.setFilledVolume(filledVolume);
            entity.setAveragePrice(averagePrice);
            entity.setFee(fee);
            entity.setFeeCurrency(feeCurrency);
            entity.setRealizedProfit(realizedProfit);
            entity.setMessage(message);
            entity.setUpdatedAt(LocalDateTime.now());
            legMapper.update(entity, new UpdateWrapper<HtxContractLegEntity>()
                    .eq("cycle_id", cycleId).eq("leg_type", legType));
        } catch (Exception e) {
            log.error("contract leg update failed cycleId={} legType={} state={}",
                    cycleId, legType, state, e);
        }
    }

    /** Persist one completed simulated open/close cycle for DB/log cross-checking. */
    public long recordDryRun(HtxContractCycleRequest request, String contractCode, int leverage, String message) {
        try {
            HtxContractCycleEntity entity = new HtxContractCycleEntity();
            entity.setClientOrderId(request.getClientOrderId());
            entity.setContractCode(contractCode);
            entity.setReqVolume(request.getVolume());
            entity.setOpenPrice(request.getOpenPrice());
            entity.setClosePrice(request.getClosePrice());
            entity.setLeverage(leverage);
            entity.setStatus(DRY_RUN_DONE);
            entity.setOpenFilledVolume(BigDecimal.ZERO);
            entity.setCloseFilledVolume(BigDecimal.ZERO);
            entity.setMessage(message);
            entity.setUpdatedAt(LocalDateTime.now());
            mapper.insert(entity);
            return entity.getId();
        } catch (Exception e) {
            throw new HtxPersistenceException("contract dry-run insert failed", e);
        }
    }

    /** Post-send updates are best effort: the write-ahead INTENT remains blocking if an update fails. */
    public void update(long cycleId, String status, Long openOrderId, Long closeOrderId,
                       BigDecimal openFilled, BigDecimal closeFilled, String message) {
        try {
            HtxContractCycleEntity entity = new HtxContractCycleEntity();
            entity.setId(cycleId);
            entity.setStatus(status);
            entity.setOpenOrderId(openOrderId);
            entity.setCloseOrderId(closeOrderId);
            entity.setOpenFilledVolume(openFilled);
            entity.setCloseFilledVolume(closeFilled);
            entity.setMessage(message);
            entity.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(entity);
        } catch (Exception e) {
            log.error("contract cycle update failed cycleId={} status={}", cycleId, status, e);
        }
    }
}
