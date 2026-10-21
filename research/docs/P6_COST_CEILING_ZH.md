# P6 离线成本上界探针

依据本地论文摘录 arXiv 2610.25445 与 survey 实现 Roll/CF 的合理子集；未实现偏差修正 a*、最优凸组合或 GMM。输入只需 `ts,close`。差分用价格差 Y=ΔP。主输出单位是 close 报价单位的半价差；同时给出窗口内 `ci_upper_bp = ci_upper/median_close*1e4` 便于阅读。**不能**直接当作总交易成本、追价滑点或 PRD 成本参数。

Roll：`sqrt(max(0,-Cov(Y_t,Y_{t-1})))`，协方差总体分母 n。CF：`phi(a)=mean(cos(aY))`，`d=1-phi(2a)/phi(a)^2`，`c=atan(d^(1/4))/a`。`phi≤1e-6`、`phi(2a)<0` 或 d 不在 [0,1] 时回退同次抽样的 Roll。每窗 `a = frequency_scale / median(|Y|)`（默认 scale=1），使 a·Y 量级稳定，避免绝对价格资产上固定 a 失真。

默认窗口 2881 个 close（约 30 天 15m），步长 2880；块长 32，B=2000，seed=20261021。输出 **95% 单侧百分位块自助上界**（`method=higher`）。对相邻差分对循环块抽样。每窗输出 `roll_ci_upper` / `cf_ci_upper`，主字段 `ci_upper=max(...)`。缺/重时间戳拒绝。

合成覆盖：100 次、c=0.02、创新 sd=0.02、1025 close、B=400、块 16、路径种子 6132026，自助种子 20261021+trial；要求 Roll 上界覆盖 ≥90/100。c=0 不报错。15m 加密信噪比极差时上界可能很松，仅作诊断。

JSON 含 `point_estimate_written_to_prd=false`、`diagnostic_only=true`。**点估计未入 PRD/策略参数。** 模块不被现网 import。固定输入+参数+numpy → 逐位一致；`input_sha256` 对原始字节。

## 运行

```sh
PY=/Users/jenniferni/jenifer/venv-research/bin/python
CTX=/Users/jenniferni/jenifer/build/codex-logs/p6-cost-ceiling-20261021/context
$PY -m coinresearch.cost_ceiling_cli --input "$CTX/BTC_USDT_15m_close.csv" --out-dir outputs/p6/btc
$PY -m coinresearch.cost_ceiling_cli --input "$CTX/ETH_USDT_15m_close.csv" --out-dir outputs/p6/eth
$PY -m coinresearch.slippage_sensitivity --phase1 "$CTX/phase1_val_trades.csv" --paper "$CTX/paper_trades_closed.csv" --out-dir outputs/p6
$PY -m pytest -q
```

滑点：单边额外 0/1/2/3/5bp，往返扣 `2*bp*entry*qty*contract_size/10000`；手续费/资金费不变。phase1 CSV 为离线重构逐笔：**非登记试验、非 PRD 参数来源**；记忆窗 2024-01-25..2025-06-03，可得段 2024-10-12..2025-06-02。
