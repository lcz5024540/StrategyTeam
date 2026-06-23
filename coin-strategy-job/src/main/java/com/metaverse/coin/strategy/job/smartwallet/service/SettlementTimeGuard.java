package com.metaverse.coin.strategy.job.smartwallet.service;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;

/**
 * P2-2 写入入口防护：仓位 / 订单结算 · 平仓时间统一 clamp。
 *
 * <p>背景：commit {@code 0a37a143} 已在 {@link SmartWalletAccountingService#settlementAt}
 * 内做了 {@code [openedAt, now]} clamp，但 {@code position.closed_at} / {@code trade_order.settled_at}
 * 还有多条 bypass 路径直接取 {@code snapshot.lastFilledAt()} / {@code fill.getFilledAt()}，
 * 源时间一旦异常（乱序 fill、回填、外部时钟漂移）就会写入 {@code settled_at &lt; opened_at} 的脏
 * 数据——巡检里 16 → 24 笔的增量即由此而来。</p>
 *
 * <p>本工具把所有结算 / 平仓时间写入入口前的不变量检查收口到一处。候选时间一旦落在
 * {@code [openedAt, now]} 之外：记 ERROR / WARN 日志（便于运维与告警通道发现源头）+ clamp
 * 到合法区间 + 返回安全值。<b>生产安全</b>：不抛异常、不中断 fill / settlement 主流程，但
 * 保证 DB 不再产生"结算早于开仓 / 结算晚于当前"的脏数据。</p>
 */
@Slf4j
@UtilityClass
public class SettlementTimeGuard {

    /**
     * 把候选结算/平仓时间 clamp 到 {@code [openedAt, now]}。
     * <ul>
     *   <li>{@code candidate < openedAt} → 记 ERROR 日志（标注 context） + 返回 {@code openedAt}。
     *       这种情况通常意味着上游传入了「乱序 fill 时间」或「外部回填的过时时间」，源头需要排查。</li>
     *   <li>{@code candidate > now} → 记 WARN 日志 + 返回 {@code now}。常见于市场名义到期晚于当前。</li>
     *   <li>区间内 → 原样返回。</li>
     *   <li>{@code candidate == null} → 返回 null（仓位未关时 {@code closed_at = null} 是合法的）。</li>
     *   <li>{@code openedAt == null} → 跳过下界检查（仅做上界 clamp），并打 WARN 提示。</li>
     * </ul>
     *
     * @param candidate 候选时间（可为 null）
     * @param openedAt  仓位开仓时间下界（可为 null）
     * @param context   日志标记调用站点，例如 "appendFill" / "rebuildPositionFromFills" /
     *                  "resolvePosition" / "syncTradeOrder"
     */
    public static Date clamp(Date candidate, Date openedAt, String context) {
        if (candidate == null) {
            return null;
        }
        Date now = new Date();
        if (openedAt == null) {
            // 极少数补偿路径可能没有 openedAt（如部分 paper-only / 早期数据），跳过下界检查
            log.warn("[SETTLEMENT_TIME_ASSERT] {} openedAt=null, skip lower-bound check candidate={}",
                    context, candidate);
            if (candidate.after(now)) {
                log.warn("[SETTLEMENT_TIME_ASSERT] {} candidate={} > now={} — clamp to now",
                        context, candidate, now);
                return now;
            }
            return candidate;
        }
        if (candidate.before(openedAt)) {
            log.error("[SETTLEMENT_TIME_ASSERT] {} candidate={} < openedAt={} — clamp to openedAt; "
                            + "上游写入站点可能传入了早于开仓的时间戳, 请排查源头 (P2-2)",
                    context, candidate, openedAt);
            return openedAt;
        }
        if (candidate.after(now)) {
            log.warn("[SETTLEMENT_TIME_ASSERT] {} candidate={} > now={} — clamp to now",
                    context, candidate, now);
            return now;
        }
        return candidate;
    }
}
