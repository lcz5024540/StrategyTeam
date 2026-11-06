# 验证终端记录

2026-11-06，本机Python 3.14、websockets 15.0.1；实施目录`htx-trend-python`。

## 最终回归

```text
HTX_TEST_MYSQL_PORT=33319 /tmp/coinstrategy-public-capture-py314/bin/python -m pytest -q --tb=short
1409 passed, 89 warnings in 75.31s (0:01:15)
```

[最终原始终端输出](tests-final.log)。89条为既有test_paper_db_persist SQLite datetime adapter弃用警告。隔离容器`colima-hot-deploy-tests / hot-deploy-mysql`，非生产DB；测试后停止，数据保留。复测前一轮在容器刚启动时7个MySQL setup连接错误（1402 passed），`mysqladmin ping`确认alive后全量重跑得到上述结果，不隐去环境失败。

```text
pytest tests/test_public_ws_diagnostics.py tests/test_realtime_public_capture.py tests/test_realtime_public_capture_mdqueue_b.py -q --tb=short
59 passed in 2.00s
python -m compileall -q src/htx_trend
exit 0
ruff check --isolated --select E9,F63,F7,F82 <两个变动模块及新增测试>
All checks passed!
git diff --check
exit 0
```

## 真实HTX公共WS进程

命令：`PYTHONPATH=src /tmp/coinstrategy-public-capture-py314/bin/python ../docs/htx-trend-python/evidence/ws-diagnostics-20261106/public_ws_probe.py`。

实际结果（从终端JSON摘录）：

```json
{
  "elapsed_s": 23.165,
  "thread_stopped": true,
  "epoch": "b010562b1cd2482bae904fafdb997d6c",
  "observed_ms": 1793939510865,
  "counts": {"GAP": 3, "TRADE": 14, "GAP_RECOVERED": 1, "BBO": 386, "WS_CHANNEL_RECOVERED": 2, "DEPTH": 21},
  "protocol_last_rtt_ms": 107.535,
  "htx_last_pong_sent_ms": 1793939510097,
  "pending_channels": [],
  "diagnostic_errors": 0,
  "diagnostic_events_dropped": 0,
  "failure": null,
  "segments_verified_events": 427
}
```

原数据保留在`/private/var/folders/d2/pr2p05593gzfxx0bbkpy_8qm0000gp/T/cos-ws-diagnostic-smoke-xm4xo7_k/capture`，本机临时目录，不作为永久部署输入。探针最初两次分别因未设PYTHONPATH、macOS临时路径含符号链接未发网络请求；修正运行入口及resolve后得到上述成功结果，未放宽生产的symlink守卫。

首次BBO观测1793939490345，首次DEPTH1793939490447，last_good均null，正确标记首次观测而非历史恢复。断连与重连退避由fake clock测试和本机真实WebSocket loopback关闭验证，未对线上连接注入故障。

## 热路径微基准

相同Capture hook调用10万轮，每轮recv计时、raw_received、decode计时、observe，不含网络/磁盘：0.292339667秒，2.92339667µs/tick；保留channel key=1，diagnostic_errors=0。不是生产吞吐保证。

## 边界

业务仓本地提交，不push/部署；无账户、订单、生产DB、COS写入。根治理门禁历史H-10缺commit仍阻断，单独报告，不绕过。
