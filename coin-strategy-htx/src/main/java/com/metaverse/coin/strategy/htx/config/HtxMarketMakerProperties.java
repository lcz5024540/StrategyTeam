package com.metaverse.coin.strategy.htx.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * 做市策略配置，绑定 {@code application.yml} 的 {@code htx.market-maker.*}。
 * 权威值在配置文件（与现有风控约定一致），Java 仅声明兜底默认。
 * 见设计 spec 2026-08-06-htx-market-maker-design.md §5。
 */
@Data
@ConfigurationProperties(prefix = "htx.market-maker")
public class HtxMarketMakerProperties {

    /** 总开关，默认关；急停语义 = 关闭后重启不加载调度器，不开新 cell。 */
    private boolean enabled = false;

    /** 标的白名单，非白名单一律拒开。 */
    private List<String> symbols = List.of("xautusdt");

    /** 主循环轮询间隔（ms）。 */
    private long pollIntervalMs = 10_000L;

    /** 价差 δ：buyPrice=P0(1−δ)，sellPrice=P0(1+δ)。 */
    private BigDecimal spreadPct = new BigDecimal("0.003");

    /** 保守单边费率下限；运行时与 HTX V2 返回的 maker/taker 实际费率取最大值。 */
    private BigDecimal feeRatePerSide = new BigDecimal("0.002");

    /** 每 cell 固定 quote 名义（USDT）；qty = notional / buyPrice。 */
    private BigDecimal perCellQuoteNotional = new BigDecimal("12");

    /** HTX 现货卖单最低名义金额（USDT）；低于该值的残量记为 dust，禁止无限重试。 */
    private BigDecimal minSellOrderQuoteNotional = new BigDecimal("1.1");

    /** 单标的最大并行 open cell 数 K。 */
    private int maxConcurrentCellsPerSymbol = 20;

    /** 新买价与已挂买价的最小相对间距（去重防同价堆单）。 */
    private BigDecimal minGapPct = new BigDecimal("0.0009");

    /** 买单未成交超时撤单（ms）。 */
    private long buyTtlMs = 900_000L;

    /** 卖单 TTL（ms）；0 = 不设 TTL，一直挂到成交。 */
    private long sellTtlMs = 0L;

    /** 买价漂移超此相对比例（|bestBid−buyPrice|/bestBid）即撤旧买单、在新 bestBid 重挂（跟价）。 */
    private BigDecimal repriceThresholdPct = new BigDecimal("0.001");

    /** 可用 USDT 不足时暂停所有新买单的时长（ms），到期后重新查余额自动恢复。 */
    private long insufficientBalanceBackoffMs = 300_000L;

    /** 零成交买单被交易所取消后，同一/近似价位暂停重开的时长（ms）；0 = 不启用。 */
    private long buyCancelRetryCooldownMs = 300_000L;

    /** 卖腿跟价+止损总开关；false=退回旧「固定价挂到成交」（config-only 回退）。 */
    private boolean sellFollowEnabled = true;

    /** 止损总开关；默认关闭，避免卖价跌破含费买入成本。 */
    private boolean stopLossEnabled = false;

    /** 跌破 buy·(1−此值) 触发 taker 割肉；仅 stopLossEnabled=true 时生效。 */
    private BigDecimal stopLossPct = new BigDecimal("0.005");

    /** 卖价漂移超此比例才重挂（防抖、省撤单费）。 */
    private BigDecimal sellRepriceThresholdPct = new BigDecimal("0.001");

    /** 卖单挂太久 → 保本(容忍微亏)吃单退出 总开关；false=完全关闭本功能（config-only 回退）。 */
    private boolean sellLingerExitEnabled = false;

    /** 卖单在 RESTING_SELL 挂满此时长（ms）才触发 linger taker 退出。 */
    private long sellLingerTimeoutMs = 120_000L;

    /** linger 退出容忍亏损比例：(1−f)²·bestBid ≥ buy·(1−此值) 即可吃单。 */
    private BigDecimal sellLingerLossTolerancePct = new BigDecimal("0.002");

    /** 买单挂满 buyTtlMs 仍未成交 → 穿价吃 bestAsk 以 taker 入场 总开关；false=完全关闭本功能（config-only 回退）。 */
    private boolean buyTakerEscalationEnabled = true;

    /** taker 买价差闸门：仅当 bestAsk ≤ bestBid·(1+此值) 才穿价吃单；太宽退回现状撤单重开。 */
    private BigDecimal buyTakerMaxCrossPct = new BigDecimal("0.001");

    /** 网格全梯补齐总开关；false=完全回退现状单格逻辑（config-only 回退），行为逐字节等价。 */
    private boolean gridModeEnabled = false;

    /** 单个轮询周期内最多新增的网格买单数；限制窗口迁移或深度抖动时的集中撤挂，仍可保持近市场高频报价。 */
    private int gridMaxNewCellsPerTick = 2;

    /** 对冲恢复队列存在 HEDGE_DEGRADED/UNKNOWN 时，禁止新增现货库存；已有仓位仍继续对账与卖出。 */
    private boolean requireHealthyHedgeForNewBuys = true;

    /** 网格模式下，卖腿按当前移动窗口/盘口重评估；窗口价只能抬高含费保本线，不能把历史库存压到亏损出售。 */
    private boolean gridSellFollowWindowEnabled = false;

    /** 等比步长（每档下移比例）：L_k=anchor·(1−此值)^k；与 spreadPct 同量级，保证每格卖腿仍满足不亏。 */
    private BigDecimal gridStepPct = new BigDecimal("0.001");

    /** 理想价梯档数上限（实际受 maxConcurrentCellsPerSymbol / 敞口 / 地板裁剪）。 */
    private int gridLevels = 20;

    /** 价格越过窗口边界后还需多走的比例，避免边界附近来回迁移。 */
    private BigDecimal gridMigrationHysteresisPct = new BigDecimal("0.0005");

    /** 同方向连续越界达到该次数后才迁移。 */
    private int gridMigrationConfirmations = 2;

    /** 两次窗口迁移的最短间隔（ms）。 */
    private long gridMigrationCooldownMs = 30_000L;

    /** 单 tick 最多迁移档数，限制跳空时的撤挂规模。 */
    private int gridMaxShiftStepsPerTick = 20;

    /** 绝对价格地板（per-symbol）：低于此价一律不开买单；0 或负数视为不设地板。深跌硬止接盘。 */
    private BigDecimal gridFloorPrice = BigDecimal.ZERO;

    /** 单 symbol 所有未平 cell 买入名义之和上限（USDT）：Σ(open buy notional)+perCell ≤ 此值才开新档。 */
    private BigDecimal maxTotalExposureQuoteNotional = new BigDecimal("240");

    /** 新增买单后仍必须保留的可用 quote 余额；0 = 不启用储备闸。 */
    private BigDecimal minQuoteReserve = BigDecimal.ZERO;

    /** 新增买单后允许持有的最大 base 库存；0 = 不启用库存闸。 */
    private BigDecimal maxBaseInventory = BigDecimal.ZERO;

    /** 当日已实现净亏损预算（USDT）：当日累计净亏损超过此值即停开新单（熔断）。 */
    private BigDecimal dailyNetLossBudget = new BigDecimal("5");

    /** 连续亏损退出 cell 数上限：达到即停开新单（内存态，重启清零，保守偏安全）。 */
    private int maxConsecutiveLossCycles = 3;
}
