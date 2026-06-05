#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
策略运行状况巡检脚本（开单 / 胜率 / 卡点）
在【可访问 49.51.250.174 的环境】运行（服务器本机或网络可达的机器）。
依赖: pip install pymysql
用法: python3 diagnose.py
说明: 首次运行会自动 DESCRIBE 关键表并打印列名，便于按真实列名校准统计 SQL。
"""
import pymysql

DB = dict(host="49.51.250.174", port=3306, user="root",
          password="Lroot1234", database="coin_strategy",
          charset="utf8mb4", connect_timeout=10)

# 当前版本主策略相关表（按需调整）
ORDER_T   = "polymarket_follow_order"
POS_T     = "polymarket_follow_position"
SIGNAL_T  = "polymarket_follow_signal"
# 平仓/成交状态枚举（请按 DESCRIBE 结果校准）
CLOSED_STATUS = "('CLOSED','DONE')"
DONE_STATUS   = "('FILLED','CANCELED','CLOSED','DONE')"


def run(cur, title, sql):
    print("\n" + "=" * 70)
    print("# " + title)
    print("-" * 70)
    try:
        cur.execute(sql)
        rows = cur.fetchall()
        cols = [d[0] for d in cur.description] if cur.description else []
        if cols:
            print(" | ".join(cols))
        for r in rows:
            print(" | ".join("" if v is None else str(v) for v in r))
        if not rows:
            print("(无数据)")
    except Exception as e:
        print("查询失败:", repr(e))


def main():
    try:
        conn = pymysql.connect(**DB)
    except Exception as e:
        print("数据库连接失败:", repr(e))
        print("请确认本机可访问 49.51.250.174:3306（巡检环境网络白名单/跳板机）。")
        return
    cur = conn.cursor()

    # 0. 结构校准
    for t in (ORDER_T, POS_T, SIGNAL_T):
        run(cur, f"DESCRIBE {t}", f"DESCRIBE {t}")

    # 1. 开单是否正常
    run(cur, "最近一次开单时间", f"SELECT MAX(create_time) FROM {ORDER_T}")
    run(cur, "近24h开单量", f"SELECT COUNT(*) FROM {ORDER_T} WHERE create_time >= NOW()-INTERVAL 1 DAY")
    run(cur, "近7天每日开单量",
        f"SELECT DATE(create_time) d, COUNT(*) cnt FROM {ORDER_T} "
        f"WHERE create_time >= NOW()-INTERVAL 7 DAY GROUP BY DATE(create_time) ORDER BY d")
    run(cur, "卡住的订单(非终态)",
        f"SELECT status, COUNT(*) cnt, MIN(create_time) oldest FROM {ORDER_T} "
        f"WHERE status NOT IN {DONE_STATUS} GROUP BY status")

    # 2. 胜率是否正常
    run(cur, "总体胜率与盈亏",
        f"SELECT COUNT(*) closed_cnt, "
        f"SUM(CASE WHEN profit>0 THEN 1 ELSE 0 END) win_cnt, "
        f"ROUND(SUM(CASE WHEN profit>0 THEN 1 ELSE 0 END)/COUNT(*)*100,2) win_rate_pct, "
        f"ROUND(AVG(profit),4) avg_pnl FROM {POS_T} WHERE status IN {CLOSED_STATUS}")
    run(cur, "近7/30天胜率",
        f"SELECT '7d' p, ROUND(SUM(CASE WHEN profit>0 THEN 1 ELSE 0 END)/COUNT(*)*100,2) win_rate "
        f"FROM {POS_T} WHERE status IN {CLOSED_STATUS} AND update_time>=NOW()-INTERVAL 7 DAY "
        f"UNION ALL SELECT '30d', ROUND(SUM(CASE WHEN profit>0 THEN 1 ELSE 0 END)/COUNT(*)*100,2) "
        f"FROM {POS_T} WHERE status IN {CLOSED_STATUS} AND update_time>=NOW()-INTERVAL 30 DAY")
    run(cur, "分策略胜率",
        f"SELECT strategy_id, COUNT(*) closed_cnt, "
        f"ROUND(SUM(CASE WHEN profit>0 THEN 1 ELSE 0 END)/COUNT(*)*100,2) win_rate_pct, "
        f"ROUND(SUM(profit),4) total_pnl FROM {POS_T} WHERE status IN {CLOSED_STATUS} "
        f"GROUP BY strategy_id ORDER BY total_pnl")

    # 3. 卡点
    run(cur, "信号vs订单(近24h)",
        f"SELECT (SELECT COUNT(*) FROM {SIGNAL_T} WHERE create_time>=NOW()-INTERVAL 1 DAY) signals_24h, "
        f"(SELECT COUNT(*) FROM {ORDER_T} WHERE create_time>=NOW()-INTERVAL 1 DAY) orders_24h")
    run(cur, "堆积未消费信号",
        f"SELECT status, COUNT(*) cnt, MIN(create_time) oldest FROM {SIGNAL_T} "
        f"WHERE create_time>=NOW()-INTERVAL 2 DAY GROUP BY status")
    run(cur, "长龄未成交订单TOP50",
        f"SELECT id, status, create_time, TIMESTAMPDIFF(MINUTE,create_time,NOW()) age_min "
        f"FROM {ORDER_T} WHERE status NOT IN {DONE_STATUS} ORDER BY create_time ASC LIMIT 50")
    run(cur, "长期未平持仓TOP50",
        f"SELECT id, strategy_id, status, create_time, TIMESTAMPDIFF(HOUR,create_time,NOW()) hold_hours "
        f"FROM {POS_T} WHERE status NOT IN {CLOSED_STATUS} ORDER BY create_time ASC LIMIT 50")

    cur.close()
    conn.close()
    print("\n巡检完成。")


if __name__ == "__main__":
    main()
