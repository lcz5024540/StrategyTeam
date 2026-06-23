package com.metaverse.coin.strategy.job.engine.wallet;

import java.util.List;

/**
 * 多策略钱包管理接口。
 *
 * <p>管理 N 个策略的独立钱包。每个 walletId 对应一个 Polymarket 执行地址 + 私钥。</p>
 *
 * <p><b>零侵入</b>：此接口独立于现有的 {@code ExecutionWalletContextLoader}，
 * 现有跟单策略继续使用自己的 ExecutionWalletContextLoader，
 * 新策略通过 MultiWalletManager 获取钱包上下文。
 * Phase 1 适配时再将跟单钱包注册到此管理器。</p>
 */
public interface MultiWalletManager {

    /** 获取指定钱包的上下文（凭证 + 配置） */
    WalletContext getWalletContext(String walletId);

    /** 获取指定策略绑定的所有钱包 */
    List<WalletContext> getWalletsByStrategy(String strategyId);

    /** 列出所有已注册钱包的状态 */
    List<WalletStatusView> listWallets();

    /** 刷新钱包配置缓存（从数据库重新加载） */
    void refreshCache();
}
