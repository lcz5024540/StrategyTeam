package com.metaverse.coin.strategy.job.engine.model;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 策略单轮信号扫描 + 下单的运行结果。
 */
@Data
@Builder
public class StrategyCycleResult {

    /** 策略标识 */
    private String strategyId;

    /** 运行开始时间 */
    private Instant startedAt;

    /** 运行持续时间 */
    private Duration duration;

    /** 本轮发现的信号数 */
    private int signalsFound;

    /** 本轮实际下单数 */
    private int ordersPlaced;

    /** 本轮被风控拒绝的信号数 */
    private int signalsRejected;

    /** 本轮发生的错误数 */
    private int errors;

    /** 产生的信号列表 */
    private List<TradingSignal> signals;

    /** 错误详情 */
    private List<String> errorMessages;

    /** 是否有致命错误导致策略中断 */
    private boolean fatalError;
}
