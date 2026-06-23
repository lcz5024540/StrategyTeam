package com.metaverse.coin.strategy.job.smartwallet.model;

/**
 * 执行模式。
 *
 * <p>历史上只有 {@link #PAPER} 和 {@link #LIVE} 两种。F-P0-6 第二轮新增
 * {@link #DRY_RUN}：用于预演（dry-run）路径产出的"假执行结果"显式打标，
 * 让账本 / 影子记账可以区分这是来自 {@code dryRunFollowTask}（线上不会真的下单）
 * 还是真的 LIVE / PAPER 路径。</p>
 *
 * <p>使用规则：</p>
 * <ul>
 *   <li>{@link #LIVE} — 真实链上下单</li>
 *   <li>{@link #PAPER} — 虚拟 paper-book 撮合（仍会写入 smart_wallet_order / fill 表，只是 executionAddress=paper-book）</li>
 *   <li>{@link #DRY_RUN} — 预演输出，<b>不</b>写主账本表；调用方需要显式选择是否将其投递到
 *     replay / shadow 通道</li>
 * </ul>
 *
 * <p>注意：以前的 {@code if (mode == ExecutionMode.LIVE)} 判断对 DRY_RUN 来说统统判为非 LIVE，
 * 这是期望的行为（DRY_RUN 不应走 LIVE 执行通路）。{@link #isVirtual()} 提供一个语义清晰的辅助方法。</p>
 */
public enum ExecutionMode {
    PAPER,
    LIVE,
    /** F-P0-6：预演路径显式标记，便于账本 / 影子记账区分。 */
    DRY_RUN;

    /** 是否为"非真钱"执行（PAPER 或 DRY_RUN）。 */
    public boolean isVirtual() {
        return this != LIVE;
    }

    /** 是否走真实链上 LIVE 通路。 */
    public boolean isLive() {
        return this == LIVE;
    }
}
