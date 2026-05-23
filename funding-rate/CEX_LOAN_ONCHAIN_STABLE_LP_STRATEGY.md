---
title: "CEX借贷-链上Stable LP套利策略 (CEX Loan to On-chain Stable LP Strategy)"
type: strategy
status: active
tags: [onchain-lp, strategy]
created: 2026-04-10
updated: 2026-04-10
---

# CEX借贷-链上Stable LP套利策略 (CEX Loan to On-chain Stable LP Strategy)

## 1. 策略概述 (Overview)
**策略名称**: CEX借贷-链上稳定币LP套利 (CEX Loan to On-chain Stable LP Arbitrage)
**核心逻辑**: 当中心化交易所 (CEX) 的稳定币借贷利率 (Borrow APR) 显著低于 链上 DeFi 协议的稳定币 LP 质押收益率 (Supply/Stake APY) 时，通过在 CEX 抵押资产借出稳定币，提现至链上提供流动性 (LP) 并质押，以此赚取利差。
**适用场景**: 熊市或震荡市，CEX 借贷需求低 (利率低)，而链上某些协议有高额激励。

## 2. 成本计算模型 (Cost Analysis)

本策略的核心在于**精细化的成本计算**。只有当 `预计收益 > 总成本 + 风险溢价` 时，才执行策略。

### 2.1 显性成本 (Explicit Costs)
1.  **借贷成本 (Borrowing Interest)**:
    *   计算公式: `借款数量 * 借贷日利率 * 持仓天数`
    *   *注意: CEX 利率通常浮动，需实时监控。*
2.  **交易成本 (Trading Fees)**:
    *   **CEX端**: 如果需要买入抵押品，会有现货交易手续费。
    *   **链上端 (Swap)**: 组 LP 时若需将单一稳定币换成两种 (如 USDT -> USDT+USDC)，会有 Swap 手续费 (0.01% - 0.05%) + 滑点。
3.  **提币/充币成本 (Transfer Fees)**:
    *   **CEX -> 链上**: 提币费 (Withdrawal Fee)，以太坊主网较贵 (3-5 U)，L2 较便宜 (<0.1 U)。
    *   **链上 -> CEX**: 归集时的 Gas 费。
4.  **链上交互 Gas 费 (On-chain Gas)**:
    *   这是一个固定成本，对于小资金极其敏感。
    *   **操作流程**: `Approve` (授权) -> `Add Liquidity` (组LP) -> `Stake` (质押) -> `Claim Reward` (领收益) -> `Unstake` (解质押) -> `Remove Liquidity` (拆LP) -> `Swap` (换回单币) -> `Transfer` (转回CEX)。
    *   *建议: 预估全流程 Gas (例如 0.05 ETH)，将其视为一次性损耗。*

### 2.2 隐性成本 (Implicit Costs)
1.  **资金占用成本**: 抵押在 CEX 的资产 (如 BTC/ETH) 无法参与其他高收益策略。
2.  **滑点 (Slippage)**: 链上大额兑换时的价格损耗。

### 2.3 盈亏平衡公式
$$ NetProfit = (APY_{LP} - APR_{Loan}) \times Principal \times Days / 365 - (Gas_{Total} + Fees_{Transfer} + Fees_{Swap}) $$

## 3. 风险点分析 (Risk Analysis)

### 3.1 市场风险
1.  **LP 撤池/脱锚风险 (De-peg Risk)**:
    *   虽然是稳定币 LP (如 USDT-USDC)，但若其中一币脱锚 (如 USDC 脱锚事件)，LP 价值将受损。
    *   *对策: 仅选择蓝筹稳定币 (USDT, USDC)，避开算稳。*
2.  **利率倒挂风险**:
    *   CEX 借贷利率飙升，或 链上 APY 骤降，导致利差消失甚至亏损。
    *   *对策: 设置平仓阈值，一旦 (APY - APR) < 2%，立即止盈/止损。*
3.  **抵押品清算风险**:
    *   CEX 抵押品 (如 BTC) 价格大跌导致 LTV 触警。
    *   *对策: 保持低 LTV (如 < 50%)，并设置自动补仓或还款告警。*

### 3.2 技术/平台风险
1.  **智能合约风险**: DeFi 协议被黑客攻击。
    *   *对策: 仅参与经过多重审计、TVL 极高的头部协议 (Curve, Aave, Uniswap)。*
2.  **CEX 提充限制**: 极端行情下 CEX 可能暂停提币或充值。

## 4. 详细执行流程 (Execution Steps)

### 阶段一：机会扫描 (Monitor & Analyze)
1.  **获取 CEX 利率**: 轮询 Binance/OKX 的 USDT/USDC 借贷利率 (Hourly Interest)。
2.  **获取链上 APY**: 调用 DeFi 协议 (或通过 DefiLlama API) 获取目标池 (如 Curve 3pool) 的 Base APY + Reward APY。
3.  **Gas 预估**: 获取当前链上 Gas Price，估算全流程 Gas 费。
4.  **试算**: 输入本金 (例如 10,000 U)，计算 7天/30天 的预期净收益。

### 阶段二：执行开仓 (Open Position)
1.  **质押借币**: 在 CEX 划转 BTC/ETH 至杠杆/质押借币账户，借出 USDT。
2.  **提现**: 将 USDT 提现至链上钱包 (优先选 TRC20/BSC/Arbitrum 等低费网络，但需确保 DeFi 协议支持)。
3.  **链上操作**:
    *   `Swap`: 50% USDT -> USDC (如果组 LP 需要)。
    *   `Add Liquidity`: 注入 USDT+USDC 获得 LP Token。
    *   `Stake`: 将 LP Token 质押到 Mining 合约。

### 阶段三：监控与风控 (Monitor & Risk Control)
1.  **LTV 监控**: 每分钟检查 CEX 抵押率。
2.  **利差监控**: 每小时计算 (链上 APY - CEX 利率)。
3.  **自动预警**: 当净收益率接近 0 或 LTV > 70% 时发送钉钉/Telegram 报警。

### 阶段四：平仓结清 (Unwind)
1.  **链上赎回**: Claim 奖励 -> Unstake -> Remove Liquidity -> Swap 为 USDT。
2.  **充值还款**: USDT 充值回 CEX -> 归还本金 + 利息 -> 释放抵押品。

## 5. 技术架构建议 (现有架构扩展)

建议在 `coin-strategy-core` 中扩展 `onchain` 模块，或新建 `coin-strategy-onchain` 模块。

### 5.1 新增模块与依赖
*   **Web3 交互**: 引入 `Web3j` (Java) 或通过外部 Node.js 服务 (Ethers.js) 交互。
*   **DeFi 数据**: 集成 DefiLlama API 或 Graph Protocol (GraphQL) 获取实时 APY。

### 5.2 核心类设计
```java
// 1. 利率监控服务
public interface InterestRateService {
    BigDecimal getCexBorrowRate(String asset); // CEX 借贷利率
    BigDecimal getOnChainApy(String protocol, String pool); // 链上 APY
}

// 2. 成本计算器
public class ArbitrageCalculator {
    public AnalysisResult calculate(BigDecimal principal, int days) {
        // 计算 CEX 利息
        // 计算 Gas (需预设 Gas Limit 和当前 Gas Price)
        // 计算 跨链/交易 手续费
        // 返回 净利润 和 ROI
    }
}

// 3. 策略执行器
public class OnChainArbitrageStrategy extends BaseStrategy {
    @Override
    public void run() {
        if (calculator.isProfitable()) {
            // 发送执行信号 (鉴于链上操作复杂性，建议初期仅以此生成“操作建议”，人工执行)
            alertService.send("发现套利机会: 预计年化 " + result.getApy());
        }
    }
}
```

---

<!-- kb-backlink -->
> 本文归属：[交易策略 知识地图](../docs/MOC-交易策略.md) ｜ [知识库首页](../docs/知识库首页.md)
