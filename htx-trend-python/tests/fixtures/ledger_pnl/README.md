# 盈亏回归证据片段

从任务 t1pack 只读证据提取，原始证据不纳入提交。

- positions.json：db_positions.txt 的仓位标识、状态和 final_net_profit。
- ledger.json：db_ledger.txt 的事件标识、类型、关联和金额。
- fills.json：state.json 的成交价格、数量、费用和时间，经原始订单关联仓位；面值来自 contexts，不从 DB 盈亏反推。
- trades_closed_window.csv：窗口原始 11 笔，包含晚于 09:29 汇总的 20186。
- aggregates.txt：03-mysql-pnl-agg.txt 的账户与仓位汇总两个片段。

明细含 14 个已平和 1 个未平仓位；09:29 截止复现 13 笔。旧窗为 325、327、329 共 3 笔。保留小数文本，无连接凭据。
