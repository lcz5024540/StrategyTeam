package com.metaverse.coin.strategy.job.engine.control;

/**
 * 命令派发返回值。
 *
 * <p>{@link #message} 用于写入 {@code strategy_manual_trigger.message}（人类可读摘要、TEXT），
 * {@link #payload} 在非空时由 consumer 用 ObjectMapper 序列化为 JSON 写入
 * {@code strategy_manual_trigger.result_json}（MEDIUMTEXT），承载结构化响应。</p>
 *
 * <p>简单命令直接 {@link #text(String)} 即可；需要结构化响应的命令用
 * {@link #of(String, Object)}。</p>
 */
public record TriggerDispatchResult(String message, Object payload) {

    public static TriggerDispatchResult text(String message) {
        return new TriggerDispatchResult(message, null);
    }

    public static TriggerDispatchResult of(String message, Object payload) {
        return new TriggerDispatchResult(message, payload);
    }
}
