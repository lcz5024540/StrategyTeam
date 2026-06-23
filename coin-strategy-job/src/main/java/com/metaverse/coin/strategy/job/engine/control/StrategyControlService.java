package com.metaverse.coin.strategy.job.engine.control;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyEngineFlagMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyEngineFlagEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 策略控制服务（job 端·精简版）。
 *
 * <p>HTTP 接口已下沉到 web (see {@code StrategyEngineControlService})。
 * 本服务仅保留供 job 端其它组件（策略 runCycle 前的 kill-switch 检查）使用的查询方法。</p>
 *
 * <p>kill-switch 状态从 DB strategy_engine_flag 读取，使用短期内存缓存（2.5s）
 * 避免每个 cycle 都查 DB。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StrategyControlService {

    private static final String LOG_PREFIX = "[STRATEGY_CONTROL]";

    /** kill_switch 标志位 key（与 web 端 StrategyEngineControlService 保持一致） */
    private static final String FLAG_KILL_SWITCH = "kill_switch";
    private static final String VALUE_ACTIVE = "ACTIVE";

    /** 缓存 TTL（毫秒）— 既减轻 DB，又保证 activate/deactivate 在 2.5s 内可见。 */
    private static final long CACHE_TTL_MS = 2_500L;

    private final StrategyEngineFlagMapper flagMapper;

    /** 内存缓存：[lastFetchEpochMs, flagSnapshot] */
    private final AtomicReference<KillSwitchCache> cacheRef = new AtomicReference<>();

    // ── 熔断状态查询（供策略 runCycle 检查） ─────────────────────────

    public boolean isKillSwitchActive() {
        KillSwitchCache snapshot = currentSnapshot();
        return snapshot != null && snapshot.active;
    }

    public Instant getKillSwitchActivatedAt() {
        KillSwitchCache snapshot = currentSnapshot();
        return snapshot != null ? snapshot.activatedAt : null;
    }

    public String getKillSwitchReason() {
        KillSwitchCache snapshot = currentSnapshot();
        return snapshot != null ? snapshot.reason : null;
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    private KillSwitchCache currentSnapshot() {
        long now = System.currentTimeMillis();
        KillSwitchCache cached = cacheRef.get();
        if (cached != null && now - cached.fetchedAtMs < CACHE_TTL_MS) {
            return cached;
        }
        KillSwitchCache fresh = loadFromDb(now);
        cacheRef.set(fresh);
        return fresh;
    }

    private KillSwitchCache loadFromDb(long now) {
        try {
            StrategyEngineFlagEntity flag = flagMapper.selectOne(
                    new LambdaQueryWrapper<StrategyEngineFlagEntity>()
                            .eq(StrategyEngineFlagEntity::getFlagKey, FLAG_KILL_SWITCH)
                            .last("LIMIT 1"));
            if (flag == null) {
                return new KillSwitchCache(false, null, null, now);
            }
            boolean active = VALUE_ACTIVE.equalsIgnoreCase(flag.getFlagValue());
            Instant activatedAt = (active && flag.getActivatedAt() != null)
                    ? flag.getActivatedAt().toInstant() : null;
            String reason = active ? flag.getReason() : null;
            return new KillSwitchCache(active, activatedAt, reason, now);
        } catch (Exception e) {
            log.error("{} 加载 kill-switch 状态失败，按未激活处理: {}", LOG_PREFIX, e.getMessage(), e);
            return new KillSwitchCache(false, null, null, now);
        }
    }

    private static final class KillSwitchCache {
        final boolean active;
        final Instant activatedAt;
        final String reason;
        final long fetchedAtMs;

        KillSwitchCache(boolean active, Instant activatedAt, String reason, long fetchedAtMs) {
            this.active = active;
            this.activatedAt = activatedAt;
            this.reason = reason;
            this.fetchedAtMs = fetchedAtMs;
        }
    }
}
