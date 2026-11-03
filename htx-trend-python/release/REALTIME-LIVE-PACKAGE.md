# 15m实时结构LIVE待启用代码包

`build-live --realtime`生成独立的`0.1.7-realtime-live-<8位提交>`，策略身份为`HTX_REALTIME_STRUCTURE_15M_V1`，周期为15min／60min，实例模板为`htx-trend-live-primary`。它不是旧`HTX_TREND_15M_V2`包，也不是将PAPER候选包改名。

## 当前边界

- 包含Python实时行情、原20/32结构计划、两类触发、IOC、持仓管理及研究代码。分钟／逐笔局部结构和配套目标仍为研究模块，未接入正式策略。
- 配置示例及部署示例均`HTX_RT_ENTRY_ENABLED=false`；收益准入NOT_ELIGIBLE，唯一候选未确认，试运行控制未实现。verify核对这些字段，不能把它改为已准入后沿用包校验。
- 包中JSON/env是打包参考，worker不自动加载它们作为生产配置。安装包本身不是关闭现网交易的操作；现网env及账户状态本轮没有修改。
- 包含未执行的计划表SQL；不包含新试运行状态表，因为该控制尚未实现。不能用该SQL冒充完整试运行迁移。
- 不附旧趋势授权签发工具、旧LIVE/4H部署示例或真实凭据。保留真实准入与账户单writer检查，不签发许可。

## 打包与核验

在干净、已提交的源码目录执行：

```sh
python release/package.py build-live --realtime --out-root /path/to/new/frozen
python release/package.py verify /path/to/new/frozen/<version>/<archive>.tar.gz
```

保存tar.gz、同目录MANIFEST.json、BUILD.json及.sha256；核对source_commit、working_tree_dirty=false、实际包SHA及每文件hash。verify证明代码包一致性，不代表成交、收益或部署准入通过。

## 启用前仍需完成

唯一候选及参数确认、2U/20U和24h/10笔持久控制、独立试运行许可、费用覆盖、持仓归属及单writer切换、隔离迁移/恢复和端到端验收。按受控试运行计划形成新的最终冻结操作单；新增代码后必须重新打包，不能沿用本包hash授权不同源码。

回滚先停止新入场，继续用兼容新仓的代码管理已有仓位和未知订单；不得切回不认识新模型的旧包导致漏管。
