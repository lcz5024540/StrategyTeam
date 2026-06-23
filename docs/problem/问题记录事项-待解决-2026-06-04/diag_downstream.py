import pymysql
DB=dict(host="49.51.250.174",port=3306,user="root",password="Lroot1234",database="coin_strategy",charset="utf8mb4",connect_timeout=10,read_timeout=30)
conn=pymysql.connect(**DB);cur=conn.cursor()
def q(t,sql):
    print("\n#### "+t)
    try:
        cur.execute(sql);rows=cur.fetchall();cols=[d[0] for d in cur.description] if cur.description else []
        if cols:print(" | ".join(cols))
        for r in rows:print(" | ".join("" if v is None else str(v) for v in r))
        if not rows:print("(no rows)")
    except Exception as e:print("ERR:",repr(e))
q("OPEN positions age","SELECT MIN(opened_at) oldest, MAX(opened_at) newest, ROUND(AVG(TIMESTAMPDIFF(HOUR,opened_at,NOW())),1) avg_hold_h, MAX(TIMESTAMPDIFF(HOUR,opened_at,NOW())) max_hold_h FROM smart_wallet_trade_order WHERE status='OPEN'")
q("trade_order settled freshness","SELECT MAX(settled_at) last_settle, MAX(updated_at) last_upd FROM smart_wallet_trade_order")
q("cash_ledger freshness","SELECT MAX(occurred_at) last_entry, COUNT(*) total, NOW() now FROM smart_wallet_cash_ledger")
q("daily_report all dates","SELECT report_date,total_orders,win_rate,ROUND(net_pnl_usdc,2) net FROM smart_wallet_daily_report ORDER BY report_date DESC LIMIT 8")
q("pending oracle age","SELECT COUNT(*) c, MIN(created_at) oldest FROM smart_wallet_order WHERE status='SETTLEMENT_PENDING_ORACLE'")
q("order_stats leaders 30d","SELECT wallet_address,`window`,closed_win_rate,settlement_win_rate,closed_order_count,average_roi_rate,generated_at FROM smart_wallet_order_stats_snapshot ORDER BY generated_at DESC LIMIT 8")
conn.close()
