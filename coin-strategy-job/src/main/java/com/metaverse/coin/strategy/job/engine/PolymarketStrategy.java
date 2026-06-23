package com.metaverse.coin.strategy.job.engine;

import com.metaverse.coin.strategy.job.engine.model.StrategyCycleResult;
import com.metaverse.coin.strategy.job.engine.model.StrategyExitResult;
import com.metaverse.coin.strategy.job.engine.model.StrategyHealthSnapshot;

/**
 * 所有 Polymarket 策略的顶层 SPI 接口。
 *
 * <p>策略引擎通过此接口发现、注册、调度策略。
 * 每个策略实现此接口，由 Spring 自动注册到 {@link StrategyRegistry}。</p>
 *
 * <p><b>设计原则</b>：</p>
 * <ul>
 *   <li>策略自治 —— 信号生成、风控、仓位计算均由策略自身完成</li>
 *   <li>钱包隔离 —— 每个策略绑定独立的 Polymarket 钱包</li>
 *   <li>模式可控 —— OFF / SHADOW / PAPER / LIVE 四档运行模式</li>
 * </ul>
 *
 * <p>现有的跟单策略和天气策略将在 Phase 1 适配此接口；
 * 新策略（BTC 涨跌、网格）直接实现此接口。</p>
 */
public interface PolymarketStrategy {

    /**
     * 策略唯一标识。
     * 推荐使用大写下划线格式，如 "SMART_WALLET_FOLLOW", "WEATHER_ARB", "BTC_MINUTE", "GRID"。
     */
    String getStrategyId();

    /** 策略显示名称（中文或英文均可，用于日志和仪表盘） */
    String getDisplayName();

    /** 策略是否启用（通常由配置文件控制） */
    boolean isEnabled();

    /** 策略绑定的钱包 ID（对应 strategy_wallet 表中的 wallet_id） */
    String getWalletId();

    /** 策略运行模式 */
    StrategyMode getMode();

    /**
     * 执行一轮信号扫描 + 下单。
     * <p>由 StrategyRegistry 的定时调度或手动触发调用。
     * 策略内部负责完整的流程：信号发现 → 风控 → 仓位计算 → 下单。</p>
     *
     * @return 本轮运行结果
     */
    StrategyCycleResult runCycle();

    /**
     * 执行一轮仓位检查 + 退出扫描。
     * <p>包括止损、止盈、到期退出等。由定时器按策略自身频率调用。</p>
     *
     * @return 退出扫描结果
     */
    StrategyExitResult runExitScan();

    /**
     * 策略健康检查。
     * <p>汇报当前持仓、余额、PNL 等运行时状态。</p>
     *
     * @return 健康快照
     */
    StrategyHealthSnapshot health();
}
