package com.metaverse.coin.strategy.job.funding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeFundingSettlementMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeFundingSettlementEntity;
import com.metaverse.coin.strategy.core.arbitrage.config.MultiExchangeFundingRateProperties;
import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import com.metaverse.coin.strategy.core.arbitrage.multi.ExchangeCost;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class CrossExchangePaperPnlService {
    private final CrossExchangeArbPositionMapper positions;
    private final CrossExchangeArbOrderMapper orders;
    private final CrossExchangeFundingSettlementMapper settlements;
    private final Map<Exchange, ExchangeCost> costs;

    public CrossExchangePaperPnlService(CrossExchangeArbPositionMapper positions,
            CrossExchangeArbOrderMapper orders, CrossExchangeFundingSettlementMapper settlements,
            MultiExchangeFundingRateProperties properties) {
        this.positions = positions;
        this.orders = orders;
        this.settlements = settlements;
        this.costs = properties.getCosts();
    }

    public PaperPnlSummary summarize(String positionId) {
        if (positionId == null || positionId.isBlank()) {
            throw new IllegalArgumentException("positionId is required");
        }
        Long positionCount = positions.selectCount(new LambdaQueryWrapper<CrossExchangeArbPositionEntity>()
                .eq(CrossExchangeArbPositionEntity::getPositionCode, positionId));
        if (positionCount == null || positionCount == 0L) {
            throw new IllegalArgumentException("paper position does not exist");
        }
        List<CrossExchangeArbOrderEntity> positionOrders = orders.selectList(
                new LambdaQueryWrapper<CrossExchangeArbOrderEntity>()
                        .eq(CrossExchangeArbOrderEntity::getPositionCode, positionId));
        List<CrossExchangeFundingSettlementEntity> positionSettlements = settlements.selectList(
                new LambdaQueryWrapper<CrossExchangeFundingSettlementEntity>()
                        .eq(CrossExchangeFundingSettlementEntity::getPositionCode, positionId));

        BigDecimal basisPnl = BigDecimal.ZERO;
        BigDecimal executionCost = BigDecimal.ZERO;
        for (CrossExchangeArbOrderEntity order : positionOrders) {
            BigDecimal quantity = value(order.getFilledQuantity());
            BigDecimal notional = quantity.multiply(value(order.getAveragePrice()));
            if ("SHORT".equals(order.getLegSide())) {
                basisPnl = basisPnl.add(notional);
            } else if ("LONG".equals(order.getLegSide())) {
                basisPnl = basisPnl.subtract(notional);
            }
            ExchangeCost exchangeCost = costs.get(Exchange.parse(order.getExchangeName()));
            if (exchangeCost != null) {
                executionCost = executionCost.add(notional.multiply(exchangeCost.oneWayTakerCost()));
            }
        }
        BigDecimal fundingIncome = positionSettlements.stream()
                .map(CrossExchangeFundingSettlementEntity::getActualAmount)
                .map(CrossExchangePaperPnlService::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PaperPnlSummary(positionId, "PAPER_PUBLIC_ESTIMATE", basisPnl,
                fundingIncome, executionCost, basisPnl.add(fundingIncome).subtract(executionCost),
                positionOrders.size(), positionSettlements.size());
    }

    private static BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record PaperPnlSummary(String positionId, String source, BigDecimal basisPnl,
            BigDecimal fundingIncome, BigDecimal estimatedExecutionCost, BigDecimal totalPnl,
            int orderCount, int settlementCount) {
    }
}
