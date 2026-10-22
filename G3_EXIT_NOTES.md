# G3-EXIT 交审短笺

分支 feat/g3-exit-legs；基线 0f95c7d3f6499afcafad5af79a724574add4d8f9。

- 完整逐腿 **9/12**，未达到 ≥11/12；全部12仓14腿已从预生成 state close orders + EXIT_DECISION 证据补齐，不再采用聚合参考通过口径。10个单腿仓中9个通过、1个窗外；两笔多腿仓不一致。
- BTC02:15 / 36733c1f：仍为 EXIT_LEGS_MISMATCH。参考09:00:21减1张profit-at-risk、09:15:54减1张trend-weakened；重放05:00:30与09:00:30各减1张profit-at-risk。缺真实bid/ask/mid、tick路径及实际评估时间；forming-open入场82610 vs 现网82633.5，重放04:45 ARM、05:00已非newly_armed。不能凭OHLC唯一恢复，未回滚A或注入参考决策。
- ETH f56d3d17：仍为 EXIT_LEGS_MISMATCH。参考19:30减5张trend-weakened-partial、19:45减2张trend-weakened；重放19:30减3张profit-at-risk、19:45减4张trend-weakened。缺真实管理盘口mid/tick路径及实际评估时间。
- ETH23:00 / 93cef710：EXIT_OUTSIDE_KLINE_COVERAGE，参考13:45:33平1张，重放无退出腿。重放end=10:30:59，固定15m最后open=12:00、60m最后open=11:00；缺13:45行情，不外推、不强平。
- summary：live_count=12，full_matches=9，entry_matches=12，extra_count=0，missing_count=0，exit_mismatch_count=2，outside_coverage_count=1。入场12/12未回退；ETH08:15持仓占用保证金、BTC09:00被真实RISK_LIMIT拒单的回归断言保留并通过。
- 验证：PYTHONPATH=src，/Users/jenniferni/jenifer/venv-research/bin/pytest -q tests/test_scan_replay.py：**18 passed**；同解释器全量 pytest -q：**460 passed**。
- 已更新CSV哈希、源码LOCKED_HASHES与MANIFEST；klines.csv SHA256保持 c9317a85fcca1b9fce1a88409f2356dd668cc22688a3d173797f1712a8aef923。两次独立CLI生成的report.json/report.md逐字节一致。
- 未修改runtime.py / paper_worker.py / exits.py / execution.py或策略参数；未回滚A forming-bar-open修复；未改PRD；未出包、未装机、未热更、未SSH；未commit/push。

改动文件：

- htx-trend-python/src/htx_trend/scan_replay.py
- htx-trend-python/tests/test_scan_replay.py
- htx-trend-python/replay_inputs/20261021/trades_closed_window.csv
- htx-trend-python/replay_inputs/20261021/MANIFEST.json
- htx-trend-python/replay_inputs/20261021/output/report.md
- htx-trend-python/replay_inputs/20261021/output/report.json
- htx-trend-python/docs/SCAN_REPLAY_T2_ZH.md
- G3_EXIT_NOTES.md

预存未跟踪的 replay_inputs/20261021/_evidence_notes/exit_legs_from_state.json 原样使用，未修改；测试依赖该证据文件，交审时应随fixture保留。
