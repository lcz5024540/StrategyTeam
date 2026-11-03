# HTX独立公共行情采集

2026-11-03 · Python · 无交易 · 数据准入NOT_ELIGIBLE。

为真实回放积累公开输入，新增 `python -m htx_trend.realtime.public_capture`。该入口不启动交易worker、不读取账户凭据、不访问私有接口、不写MySQL。

## 范围与容量
按运行中的15m配置冻结52合约池，实际包8e0a0cc4。采TRADE、BBO及depth.step0完整深度流；每15分钟刷新15min/60min/4hour各160根已闭合K线，记录真实原文及修订；资金费报价每60秒，公共REST合计≤4请求/秒，WS订阅≤10条/秒。来源错误和断线明确记录；不把订阅成功当成有数据。

独立目录位于 `/home/ubuntu/coinstrategy-research/realtime-20261103/`；独立venv，不修改交易环境。主采集数据2GiB上限，含1MiB状态预算；余量低于5GiB、队列溢出、磁盘写入失败或满24小时停止。旧数据无删除，进程不自动重启。代码/venv及短时试采额外占用单列，非主采样空间。

## 数据用途与限制
分段gzip按未压缩规范内容哈希，原文与规范记录同段关联；审计/导出逐段读取，不一次载入2GiB。回放已增加深度WS时钟与分段引用支持，不为每tick再建CAS小文件。旧REST深度/CAS引用继续兼容。

公共bundle仍缺实际基线扫描/参考决策、账户手续费、实际执行延迟、市场环境和资金费结算证据。导出保留真实观察时间，不伪造SCAN/CONTEXT/零费用；状态始终PUBLIC_INPUT_ONLY_NOT_ELIGIBLE。需要后续装配完整tape并补独立基线/真实费用与执行证据，不能把这次采集启动说成收益验收通过。

## 操作
1. 启动：`python -m htx_trend.realtime.public_capture start --contracts-file <runtime-baseline.json> --output <新目录> --seconds 86400 --max-bytes 2147483648 --min-free-bytes 5368709120`。拒绝复用已有目录。
2. 查看：读取该目录 `status.json`，检查final/stop_reason、bytes、stale_price_channels及channels；超过2秒的BBO/深度会明确列出。
3. 审计：`python -m htx_trend.realtime.public_capture audit --input <目录>`；导出到独立研究磁盘时追加 `--export <不存在的.jsonl.gz>`，不得把导出副本挤入服务器预算。
4. 停止：对该独立进程发送SIGTERM，等待status.final=true；不操作现有交易服务。

## 验证
83项实时测试通过；20秒ETH真实样本覆盖五类市场输入，147KB，hash重读/导出通过。证据在[evidence/capture-20261103](evidence/capture-20261103/)。服务器启动和覆盖快照在完成后追加。

[采集与准入边界图](diagrams/public-capture-20261103.drawio)。

## 首次服务器试采与修复
52合约首次试采在约44秒触发QUEUE_OVERFLOW，安全停止；只得到24个合约的FRAME，不能作为完整区间。原因是通用分段writer重复规范序列化和gzip9压缩跟不上吞吐。
已改为append时只序列化一次并拼接相同规范字节；公共采集显式gzip1和JSON-ready路径，其他调用保留gzip9及类型转换。内容哈希与旧读器完全一致，配额与fsync不变。84项实时回归通过；同一真实depth样本1000次的离线吞吐/占用见writer-benchmark.json。服务器重新试采后再决定长期运行。
