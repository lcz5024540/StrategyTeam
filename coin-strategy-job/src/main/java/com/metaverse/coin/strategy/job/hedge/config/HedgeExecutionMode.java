package com.metaverse.coin.strategy.job.hedge.config;

/**
 * 对冲策略执行模式。
 *
 * - PAPER：模拟仓。不接入 Polymarket CLOB，不消耗链上资金，
 *          订单以 bestAsk 即刻成交方式写入本地数据库，用于策略验证与收益回测。
 * - LIVE：实盘仓。通过 PolymarketClobClientRouter 提交真实订单，
 *         需 {@code polymarket.execution-wallet.*} 凭证就绪。
 *
 * 模式切换由 {@code polymarket.hedge.execution-mode} 配置决定，
 * 两种模式共享套利识别、Kelly 资金管理和风控逻辑。
 */
public enum HedgeExecutionMode {
    PAPER,
    LIVE
}
