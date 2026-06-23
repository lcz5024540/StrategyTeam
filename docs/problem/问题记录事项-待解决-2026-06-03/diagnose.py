import pymysql
DB=dict(host="49.51.250.174",port=3306,user="root",password="Lroot1234",database="coin_strategy",charset="utf8mb4",connect_timeout=10,read_timeout=60)
conn=pymysql.connect(**DB);cur=conn.cursor()
def q(title,sql):
    print("\n#### "+title)
    try:
        cur.execute(sql);rows=cur.fetchall()
        cols=[d[0] for d in cur.description] if cur.description else []
        if cols:print(" | ".join(cols))
        for r in rows:print(" | ".join("" if v is None else str(v) for v in r))
        if not rows:print("(no rows)")
    except Exception as e:print("ERR:",repr(e))

q("NOW","SELECT NOW()")
# ---- 1. OPEN/ORDERING ----
q("follow_task status dist","SELECT status,COUNT(*) c,MIN(created_at) mn,MAX(created_at) mx FROM smart_wallet_follow_task GROUP BY status ORDER BY c DESC")
q("last FOLLOWED follow_task","SELECT MAX(created_at) FROM smart_wallet_follow_task WHERE status='FOLLOWED'")
q("last smart_wallet_order(any) & by mode","SELECT execution_mode,COUNT(*) c,MAX(created_at) last FROM smart_wallet_order GROUP BY execution_mode")
q("smart_wallet_order status dist","SELECT status,COUNT(*) c,MAX(created_at) last FROM smart_wallet_order GROUP BY status ORDER BY c DESC")
q("follow_task hourly last 30h","SELECT DATE_FORMAT(created_at,'%m-%d %H') hr, SUM(status='FOLLOWED') followed, SUM(status='PAPER_SHADOW') paper, SUM(status='SKIPPED') skipped, COUNT(*) tot FROM smart_wallet_follow_task WHERE created_at>=NOW()-INTERVAL 30 HOUR GROUP BY hr ORDER BY hr")
q("follow_task created 24h/last open age","SELECT COUNT(*) tasks_24h FROM smart_wallet_follow_task WHERE created_at>=NOW()-INTERVAL 1 DAY")
# ---- 2. WIN RATE ----
q("trade_order status dist","SELECT status,COUNT(*) c FROM smart_wallet_trade_order GROUP BY status")
q("trade_order SETTLED winrate","SELECT COUNT(*) n, SUM(total_pnl_usdc>0) wins, SUM(total_pnl_usdc<=0) losses, ROUND(SUM(total_pnl_usdc>0)/COUNT(*)*100,2) wr_pct, ROUND(SUM(total_pnl_usdc),2) net_pnl, ROUND(AVG(roi_rate)*100,2) avg_roi_pct FROM smart_wallet_trade_order WHERE status='SETTLED'")
q("trade_order OPEN floating","SELECT COUNT(*) n, ROUND(SUM(remaining_cost_basis_usdc),2) cost_locked, ROUND(SUM(unrealized_pnl_usdc),2) unreal FROM smart_wallet_trade_order WHERE status='OPEN'")
q("trade_order worst SETTLED top8","SELECT id,market_info_id,ROUND(total_pnl_usdc,2) pnl,ROUND(roi_rate,2) roi,settled_at FROM smart_wallet_trade_order WHERE status='SETTLED' ORDER BY total_pnl_usdc ASC LIMIT 8")
q("smart_wallet_order SETTLED win/loss","SELECT status,COUNT(*) c FROM smart_wallet_order WHERE status IN('SETTLED','WIN','LOSS','CLOSED') GROUP BY status")
q("daily_report last 4d","SELECT report_date,total_orders,filled_orders,winning_positions,losing_positions,win_rate,ROUND(net_pnl_usdc,2) net,ROUND(realized_pnl_usdc,2) realized FROM smart_wallet_daily_report ORDER BY report_date DESC LIMIT 4")
q("order_stats_snapshot followed wallets 30d top","SELECT wallet_address,window,closed_win_rate,settlement_win_rate,closed_order_count,generated_at FROM smart_wallet_order_stats_snapshot WHERE window IN('30d','D30','30D') ORDER BY generated_at DESC LIMIT 8")
# ---- 3. BOTTLENECK ----
q("signal_shadow decision 24h","SELECT decision_status,COUNT(*) c FROM smart_wallet_signal_shadow WHERE created_at>=NOW()-INTERVAL 1 DAY GROUP BY decision_status ORDER BY c DESC")
q("signal_shadow reject_layer 24h","SELECT reject_layer,COUNT(*) c FROM smart_wallet_signal_shadow WHERE created_at>=NOW()-INTERVAL 1 DAY AND reject_layer IS NOT NULL AND reject_layer<>'' GROUP BY reject_layer ORDER BY c DESC")
q("follow_task skip reason 7d","SELECT primary_skip_reason,COUNT(*) c FROM smart_wallet_follow_task WHERE created_at>=NOW()-INTERVAL 7 DAY AND primary_skip_reason IS NOT NULL AND primary_skip_reason<>'' GROUP BY primary_skip_reason ORDER BY c DESC")
q("cash_ledger latest balance","SELECT execution_address,balance_after,occurred_at FROM smart_wallet_cash_ledger WHERE balance_after IS NOT NULL ORDER BY occurred_at DESC LIMIT 5")
q("cash_ledger net in-out","SELECT ROUND(SUM(CASE WHEN direction='IN' THEN amount WHEN direction='OUT' THEN -amount ELSE 0 END),2) net FROM smart_wallet_cash_ledger")
# ---- 4. ALERTS ----
q("strategy_alert unresolved recent","SELECT created_at,alert_level,alert_type,resolved,acknowledged,ROUND(metric_value,2) mv,ROUND(threshold_value,2) tv FROM strategy_alert ORDER BY created_at DESC LIMIT 25")
q("trade_event freshness","SELECT MAX(opened_at) last_open, MAX(created_at) last_ingest, NOW() now FROM smart_wallet_trade_event")
q("run_log/health rows","SELECT (SELECT COUNT(*) FROM strategy_run_log) run_log,(SELECT COUNT(*) FROM strategy_health_snapshot) health")
conn.close()
