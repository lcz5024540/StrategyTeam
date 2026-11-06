# 独立公共行情采集器断连诊断

2026-11-06 · Codex · 基线 `support_htx_python@e747f1fe`；用户批准“按照建议处理”。

## 结论

断连原因、失败现场、分通道实际观测间隔已实现并通过定向回归及真实公共 WS 验证；**没有改动重连/心跳/熔断/交易策略，没有部署或推送**。这是诊断增强，不是已修复 keepalive timeout 的声明。

## 实现与字段

| 输出 | 内容 / 口径 |
|---|---|
| 原 `GAP.diagnostic` + WARN | epoch、attempt、失败阶段、异常类型/脱敏消息/异常链、sent/received close code和reason、先收后发标志、失败现场及重试计划 |
| 现场快照 | 最后原始接收/有效市场观测及年龄；connect/recv/decode/subscribe/pong_send耗时与高水位；当前session时长/是否曾有行情；队列深度/队首事件received年龄、flush/seal耗时、退避秒数/下次尝试墙钟时间及熔断状态 |
| `protocol_heartbeat` | 真实库的interval/timeout/close_timeout和最近RTT。库无公共最后Ping/Pong时间，字段为null；RTT时间未知，不用于判断当前心跳健康 |
| `htx_heartbeat` | 最近收到的JSON ping、成功send返回的pong时间/年龄与本机响应耗时；这不是协议Pong，也不是对端已收到pong的证明 |
| `WS_CHANNEL_RECOVERED` | 每epoch每合约BBO/DEPTH最多一条，含last_good_ms、first_recovered_ms、total_outage_ms。后者用单调时钟测两次有效观测间隔，跨全部退避/失败尝试；wall_interval_ms保留墙钟差以识别时钟调整 |
| `status.ws_diagnostics` / 周期INFO | 已观测/未恢复通道列表、每通道last-good与年龄、诊断错误/可选事件丢弃计数。CLI启用本模块INFO，其他logger默认WARN，不开WS DEBUG |

旧 `GAP_RECOVERED.gap_ms` **仍是本次尝试开始到第一条有效行情**，未重新定义。首次进程启动、重启后此前没有last-good时，真实间隔为null，scope为FIRST_OBSERVATION_IN_PROCESS；不把初次连通说成历史数据连续。

恢复观测基于normalized WS原始事件，位于落盘/DEPTH catch-up之前；它说明接收恢复，**不是盘口持久化恢复、已补数或全通道持续新鲜的证明**。已恢复通道仍可能过期，须结合age与原有stale字段。队首received年龄是墙钟数据年龄，不是精确enqueue等待时间；正在消费的batch与catch-up缓冲不包含在队列深度中。异常现场是外层catch时刻，库的关闭处理可能发生在此前，不能解释为网络断点的绝对瞬间。

## 架构与兼容

[旁路数据流图](evidence/ws-diagnostics-20261106/flow.drawio)。新增`realtime/ws_diagnostics.py`只维护有界内存；`public_capture.py`接操作计时、异常与周期输出；新增`test_public_ws_diagnostics.py`回归。

- 不增加BBO/TRADE字段，不更改数据字节；MarketEvent严格构造接口保持兼容。
- 恢复使用独立kind，**不使用GAP**，避免coverage/regime消费者把恢复再次当断流；不带顶层contract，避免污染行情channel统计。
- SegmentRecorder/完整audit保留新事件；capture_prefix_export原白名单自然过滤；严格执行tape仍不接受诊断kind，与旧GAP_RECOVERED相同，未扩大策略schema。
- 原异常GAP仍走原队列。新恢复marker是可选旁路，保留512队列槽位余量，压力下跳过并计数；不会单独触发QUEUE_OVERFLOW。原市场事件队列溢出仍fail-closed。
- 不写逐tick日志或文件；常态只更新内存，恢复/异常才输出；正常状态按原status节奏生成，每次full status才journal汇总。
- 消息512字符、关闭原因256字符，异常链最多4层、各8帧，无源码行/locals；敏感URL、认证头、Cookie、常见key/token/signature脱敏。重复栈按phase+异常链调用点60秒限频，仍逐次输出消息/计数/首次和末次时间；最多64个签名，淘汰后的签名重新计数，非无限历史统计。

## 验证证据

新增22项、原采集37项，合计**59 passed**。先观察11项缺功能失败，再补实现；脱敏、分通道摘要、解码总耗时及关闭计时各有先红后绿证据。覆盖：握手失败、recv/decode/subscribe/pong_send异常、实际WebSocket loopback双向关闭、部分恢复、重复失败、退避、首次启动、墙钟回拨、异常链、限长/限频/缓存界限、诊断/日志sink自身失败、队列保护、存储round-trip、行情原字节不变。

全回归结果见[验证记录](evidence/ws-diagnostics-20261106/verification.md)。Python3.14 / websockets15.0.1；编译与限定致命问题lint通过，完整风格检查的C408（dict构造）遵循模块既有风格不做全仓格式重写。22项含参数化用例；无新依赖。

真实HTX公共WS：[可重复探针](evidence/ws-diagnostics-20261106/public_ws_probe.py)，本机23.165秒，386 BBO / 21 DEPTH / 14 TRADE / 2通道首观测事件，427条落盘事件验哈回读通过；协议RTT107.535ms，JSONpong成功，diagnostic_errors=0。仅公共stream，未加载账户、REST规格、交易worker或COS上传；无生产故障注入。该23秒观测无断连，真实超时原因仍待线上新日志。

10万次诊断hook微基准（recv+decode计时、last_raw、observe）0.29234秒，**约2.923µs/tick**，保留通道key=1、错误0。仅本机无IO微基准，不代替服务器吞吐测试或承诺消除积压。

## 五轴自审

Correctness：时间口径和初次/部分恢复明确，原策略常量/调用不变。Tests：单测、真实库loopback、HTX公共API、全套回归分层验证。Design：helper无socket控制/凭据/落盘职责。Style：保持原模块dict及函数风格，只格式化新增文件。Performance/Security：有界内存、低频输出、脱敏栈与旁路失败隔离。

变更超过100行，作为不可拆开的“诊断契约+接线+回归”原子交付保留；不夹带策略修复。缺陷账本按Python+public_capture/WS模块查无相关active条目；本轮是已知日志覆盖不足的增强，不新增未经证明的网络/服务端根因DL。源码关系工具当前不可用，采用[rg/AST证据](evidence/ws-diagnostics-20261106/code-relations.json)，不声称运行过codegraph。

## 下一步（需生产授权）

1. 用户确认部署范围是否仍仅独立COS行情采集器；不包含LIVE交易、留证配额或重连参数修改。
2. 获确认后从此分支构包、验哈、备份并部署独立服务；校验journal/schema、新COS分段与旧消费者读取。
3. 遇下一次真实断连，用同epoch关联GAP诊断、重试和逐通道恢复记录，定位网络/对端/本机阻塞原因；不提前保证连续。
