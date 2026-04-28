package com.metaverse.coin.strategy.job.strategy.application;

import com.metaverse.coin.strategy.job.strategy.model.StrategyLogFlowView;

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
