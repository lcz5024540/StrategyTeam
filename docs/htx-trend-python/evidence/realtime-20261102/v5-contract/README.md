# 当前V5成交明细契约与只读验证

2026-11-02 · 读取路径：授权服务器49.51.250.174 · 无下单、撤单、部署或数据库写入。

- 官方目录：https://www.htx.com/oplt/api/open_api/category
- 条目UUID：8cb89359-77b5-11ed-9966-1957e2a0e6a；READ权限。
- GET `/v5/trade/order/details`；contract_code、order_id、from/id游标、direct=next、limit≤100。
- `updated_time`：“更新时间，默认为撮合成交时间”；`trade_id`为成交身份；`id`仅作分页游标。文档时间范围描述存在差异，实现保守按最近3天恢复。
- [官方原文](order-details.json)；[当前适配器真实只读输出及源码SHA256](current-adapter-readonly.json)。样本为现有账户历史成交，不是本任务产生的交易，不作为策略盈亏结果。
- 成交明细只提供时间、身份、费用与数量校验；原累计投影仍是唯一数量/费用写入者。未知费用不填零，返佣符号原样保留。
- 对原始响应按order、contract、side、position_side、margin_mode、数量、均价及可用手续费核对；异常不得标COMPLETE。终态订单查询失败后继续恢复，重启不丢查询身份。
