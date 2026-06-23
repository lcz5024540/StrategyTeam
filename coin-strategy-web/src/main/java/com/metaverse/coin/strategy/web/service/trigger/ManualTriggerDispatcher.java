package com.metaverse.coin.strategy.web.service.trigger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyManualTriggerMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerAction;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerStatus;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * web 端通用的"派发命令 + 等待结果"工具。
 *
 * <p>用法：</p>
 * <pre>
 *   Result r = dispatcher.dispatchAndWait("SMART_WALLET_PROFILES_OVERRIDE_BATCH",
 *                                          "_GLOBAL_", payloadMap, 60_000L);
 *   if (!r.success()) throw new ResponseStatusException(...);
 *   MyDto dto = dispatcher.parseResultJson(r, MyDto.class);
 * </pre>
 */
@Service
public class ManualTriggerDispatcher {

    /** 默认最长等待时间（毫秒） */
    public static final long DEFAULT_MAX_WAIT_MS = 60_000L;

    /** 默认轮询间隔（毫秒） */
    public static final long DEFAULT_POLL_INTERVAL_MS = 250L;

    private final StrategyManualTriggerMapper triggerMapper;
    private final ObjectMapper objectMapper;

    public ManualTriggerDispatcher(StrategyManualTriggerMapper triggerMapper,
                                   ObjectMapper objectMapper) {
        this.triggerMapper = triggerMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 派发并等待结果。
     *
     * @param action      命令动作（参考 {@link StrategyManualTriggerAction}）
     * @param strategyId  策略 ID（无策略关联时传 {@link StrategyManualTriggerAction#GLOBAL_STRATEGY_ID}）
     * @param payload     任意可被 Jackson 序列化的对象（POJO / Map / 数组）；null 表示无 payload
     * @param maxWaitMs   最长等待毫秒（≤0 时使用默认 60s）
     * @return 命令派发结果（包含 triggerId / status / message / resultJson 等）
     */
    public Result dispatchAndWait(String action,
                                  String strategyId,
                                  Object payload,
                                  long maxWaitMs) throws InterruptedException {
        long timeoutMs = maxWaitMs <= 0 ? DEFAULT_MAX_WAIT_MS : maxWaitMs;
        Long triggerId = enqueue(action, strategyId, payload);
        return await(triggerId, timeoutMs);
    }

    /** 仅派发，不等待。返回 triggerId。 */
    public Long enqueue(String action, String strategyId, Object payload) {
        String payloadJson = null;
        if (payload != null) {
            try {
                payloadJson = objectMapper.writeValueAsString(payload);
            } catch (Exception e) {
                throw new IllegalArgumentException("payload 序列化失败: " + e.getMessage(), e);
            }
        }
        StrategyManualTriggerEntity entity = new StrategyManualTriggerEntity()
                .setStrategyId(strategyId == null || strategyId.isBlank()
                        ? StrategyManualTriggerAction.GLOBAL_STRATEGY_ID
                        : strategyId)
                .setAction(action)
                .setStatus(StrategyManualTriggerStatus.PENDING)
                .setPayload(payloadJson)
                .setRequestedAt(new Date());
        triggerMapper.insert(entity);
        return entity.getId();
    }

    /** 轮询等待某条命令完成。 */
    public Result await(Long triggerId, long maxWaitMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            StrategyManualTriggerEntity row = triggerMapper.selectById(triggerId);
            if (row == null) {
                return Result.notFound(triggerId);
            }
            String status = row.getStatus();
            if (StrategyManualTriggerStatus.COMPLETED.equals(status)
                    || StrategyManualTriggerStatus.FAILED.equals(status)) {
                return Result.from(row);
            }
            Thread.sleep(DEFAULT_POLL_INTERVAL_MS);
        }
        StrategyManualTriggerEntity row = triggerMapper.selectById(triggerId);
        return Result.timeout(triggerId, row);
    }

    /** 把 resultJson 反序列化为指定类型；null/空字符串返回 null。 */
    public <T> T parseResultJson(Result result, Class<T> type) {
        if (result == null || result.resultJson() == null || result.resultJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(result.resultJson(), type);
        } catch (Exception e) {
            throw new IllegalStateException("result_json 反序列化失败: " + e.getMessage(), e);
        }
    }

    /** 命令结果。 */
    public record Result(Long triggerId,
                         String status,
                         Boolean success,
                         String message,
                         String resultJson,
                         boolean timedOut,
                         boolean notFound) {

        static Result from(StrategyManualTriggerEntity row) {
            return new Result(row.getId(), row.getStatus(),
                    row.getSuccess(), row.getMessage(), row.getResultJson(), false, false);
        }

        static Result timeout(Long id, StrategyManualTriggerEntity row) {
            return new Result(id,
                    row != null ? row.getStatus() : StrategyManualTriggerStatus.RUNNING,
                    Boolean.FALSE,
                    "等待超时；后台仍在执行，可稍后通过 trigger id 查询。",
                    row != null ? row.getResultJson() : null,
                    true, false);
        }

        static Result notFound(Long id) {
            return new Result(id, "NOT_FOUND", Boolean.FALSE, "命令记录丢失", null, false, true);
        }
    }
}
