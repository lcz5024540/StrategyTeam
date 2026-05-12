package com.metaverse.coin.strategy.web.service.log;

import com.metaverse.coin.strategy.web.model.StrategyLogFlowView;

import java.util.List;

public interface StrategyFlowLogReader {

    List<StrategyLogFlowView> queryFlows(String source,
                                         String strategy,
                                         String q,
                                         String wallet,
                                         String eventId,
                                         String followTaskId,
                                         String marketId,
                                         Integer limit);
}
