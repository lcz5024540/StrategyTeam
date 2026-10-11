# HTX Trend Research System - Deployment Guide

**Version**: 0.1.0  
**Service Type**: Standalone Python Application  
**Trading Platform**: HTX (Huobi)

## 标准部署路径

- **部署目录**: `/opt/coinstrategy/htx-trend-py`
- **用户**: `ubuntu`（可配置，见下方「自定义用户」）
- **数据目录**: `/opt/coinstrategy/htx-trend-py/data`
- **日志目录**: `/opt/coinstrategy/htx-trend-py/logs`
- **状态文件**: `/opt/coinstrategy/htx-trend-py/status.json`
- **Heartbeat**: `/opt/coinstrategy/htx-trend-py/heartbeat.txt`
- **采集状态**: `/opt/coinstrategy/htx-trend-py/collector_state.json`

统一 systemd unit 只有一套，位于仓库 `deployment/systemd/`：

- `htx-trend-collector.service`
- `htx-trend-heartbeat.service`

`WorkingDirectory=/opt/coinstrategy/htx-trend-py`，`User=ubuntu`，`ExecStart` 使用 `/opt/coinstrategy/htx-trend-py/.venv/bin/python`。采集器走配置 `/opt/coinstrategy/htx-trend-py/configs/collector.yaml`。仓库里不得再放第二套 `.service`。

```bash
sudo mkdir -p /opt/coinstrategy/htx-trend-py/{data,logs}
sudo chown -R ubuntu:ubuntu /opt/coinstrategy/htx-trend-py
sudo cp deployment/systemd/*.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now htx-trend-collector htx-trend-heartbeat
```

### 自定义用户

如需使用非 `ubuntu` 用户：

1. 修改 systemd unit 中的 `User=` 和 `Group=`
2. 修改部署目录权限
3. `sudo systemctl daemon-reload`

---

## ⚠️ Critical Deployment Notes

### Service Architecture

This is a **standalone Python service** that operates independently:

- **Java services**: Must be **STOPPED** before deploying this system
- **Account**: Only **HTX MAIN account** (cross margin) is used
- **Sub-accounts**: Must **NOT** place orders (enforced by account guard)
- **No co-existence**: This replaces any existing Java trading services

### Account Configuration

**Allowed**:
- HTX MAIN account only
- Cross margin mode only

**Blocked**:
- All sub-accounts
- Isolated margin mode
- Any account not explicitly configured

**Enforcement**: 
```python
# Config validation on startup
account:
  account_id: "main_account_id_here"  # Your MAIN account ID
  account_type: "MAIN"                # Must be MAIN
  margin_mode: "cross"                # Must be cross
```

Order placement will be **rejected** for any other account with `PermissionError`.

---

## Pre-Deployment Checklist

### 1. Stop Java Services

```bash
# Stop all Java trading services
systemctl stop coinstrategy-*

# Verify no Java processes
ps aux | grep java | grep -i coin
# Should return empty

# Verify no locks held
# Check MySQL: SELECT * FROM strategy_locks WHERE released_at IS NULL;
```

### 2. Verify Account Configuration

```bash
# Check HTX account status
curl -X GET "https://api.hbdm.com/linear-swap-api/v1/swap_account_info" \
  -H "AccessKeyId: YOUR_KEY" \
  -H "Signature: YOUR_SIGNATURE"

# Verify:
# - Account type is MAIN
# - Margin mode is cross
# - No active orders from other systems
```

### 3. Environment Variables

```bash
# Required for authenticated fee provider (optional in MVP-1)
export HTX_API_KEY="your_api_key_here"
export HTX_SECRET_KEY="your_secret_key_here"

# Account configuration (critical)
export HTX_MAIN_ACCOUNT_ID="your_main_account_id"
```

---

## Installation

### From Package

```bash
# Extract
tar -xzf htx-trend-python-0.1.0-<sha>.tar.gz
cd htx-trend-python-0.1.0-<sha>

# Verify integrity
sha256sum -c htx-trend-python-0.1.0-<sha>.tar.gz.sha256

# Install pinned deps, then the package without resolving again
python3.12 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.lock
python -m pip install --no-deps -e .
```

### Configuration

```bash
# Copy example config
cp configs/runs/R0001.yaml configs/runs/production.yaml

# Edit with production settings
vim configs/runs/production.yaml
```

**Critical fields**:
```yaml
account:
  account_id: "YOUR_MAIN_ACCOUNT_ID"  # ⚠️ Must match HTX MAIN account
  account_type: "MAIN"                # Do not change
  margin_mode: "cross"                # Do not change

# Set to false or omit for backtest
# Set to true ONLY when ready for PAPER/LIVE
# live_trading_enabled: false  # Uncomment for PAPER/LIVE
```

---

## Running

### Backtest Mode (Safe)

```bash
# Run backtest (no real trading)
python -m htx_trend.research run configs/runs/production.yaml
```

### PAPER Mode (Requires Approval)

```bash
# 1. Create approval record
python -m htx_trend.research approve \
  --gate PRE_PAPER \
  --run-id PROD001 \
  --strategy-version v0.1.0 \
  --approver "your_name@example.com" \
  --decision APPROVED \
  --rationale "Backtest passed all requirements"

# 2. Verify approval
python -m htx_trend.research check-approval \
  --gate PRE_PAPER \
  --run-id PROD001 \
  --strategy-version v0.1.0

# 3. Enable PAPER mode in config
# Set: live_trading_enabled: true
# Set: execution_mode: PAPER

# 4. Run
python -m htx_trend.research run configs/runs/production.yaml
```

### LIVE Mode (Requires Additional Approval)

```bash
# 1. PAPER must have run for ≥14 days successfully

# 2. Create PRE_LIVE approval
python -m htx_trend.research approve \
  --gate PRE_LIVE \
  --run-id PROD001 \
  --strategy-version v0.1.0 \
  --approver "risk_officer@example.com" \
  --decision APPROVED \
  --rationale "PAPER ran 14+ days, all metrics within limits"

# 3. Verify approval
python -m htx_trend.research check-approval \
  --gate PRE_LIVE \
  --run-id PROD001 \
  --strategy-version v0.1.0

# 4. Set execution_mode: LIVE in config

# 5. Run with monitoring
python -m htx_trend.research run configs/runs/production.yaml
```

---

## Monitoring

### Health Check

`health` / `status` 同时挂在 `python -m htx_trend` 和 `python -m htx_trend.research.cli`。

```bash
# Check component health
python -m htx_trend health
# equivalently: python -m htx_trend.research.cli health

# Returns exit code 0 if healthy, 1 if unhealthy
```

**Automated monitoring**:
```bash
#!/bin/bash
# health_check.sh
while true; do
  if ! python -m htx_trend health; then
    echo "ALERT: System unhealthy" | mail -s "HTX Trend Alert" ops@example.com
  fi
  sleep 60
done
```

### Status Check

`status --json` 在没有 status 文件时必须非零退出，不要把空输出当成功。

```bash
# Get current status (nonzero if no status.json)
python -m htx_trend status --json
# equivalently: python -m htx_trend.research.cli status --json

python -m htx_trend status --json > status.json

# Check positions
jq '.positions' status.json

# Check PnL
jq '.total_pnl' status.json

# Check liquidation distance
jq '.account_liquidation_distance_pct' status.json

# Check approvals
jq '.approvals' status.json

# Check version
jq '.version_info' status.json
```

---

## Safety Mechanisms

### Account Guard

**Built-in protection**:
- Only configured MAIN account can place orders
- Sub-accounts automatically rejected
- Order attempts on wrong account raise `PermissionError`

**Verification**:
```python
from htx_trend.research.account_guard import create_main_account_guard

# This will be validated on startup
guard = create_main_account_guard("YOUR_MAIN_ACCOUNT_ID")

# Any order on wrong account will be rejected
guard.require_permission("sub_account_123")  # Raises PermissionError
```

### Circuit Breakers

**Automatic stops**:
- Daily loss limit
- Consecutive losses (8 in a row)
- Drawdown limit (10%)

**Manual restart** after circuit breaker requires `BREAKER_RESTART` approval:
```bash
python -m htx_trend.research approve \
  --gate BREAKER_RESTART \
  --run-id PROD001 \
  --strategy-version v0.1.0 \
  --approver "supervisor@example.com" \
  --decision APPROVED \
  --rationale "Circuit breaker triggered by <reason>, issue resolved"
```

---

## Rollback

### Emergency Stop

```bash
# Stop the Python service immediately
pkill -f "htx_trend.research"

# Verify stopped
ps aux | grep htx_trend
# Should return empty (except grep itself)
```

### Rollback to Previous Version

```bash
# 1. Stop current version
pkill -f "htx_trend.research"

# 2. Extract previous version
tar -xzf htx-trend-python-<previous-version>-<sha>.tar.gz
cd htx-trend-python-<previous-version>-<sha>

# 3. Install
pip install -e .

# 4. Verify version
python -c "from htx_trend.research import __version__; print(__version__)"

# 5. Restart
python -m htx_trend.research run configs/runs/production.yaml
```

### Database Rollback

**v0.1.0 added**:
- `approvals` table

**If rolling back from v0.1.0**:
```sql
-- Backup first
.backup ledger.db.backup

-- Drop new table (optional, data will just be ignored by old version)
DROP TABLE IF EXISTS approvals;
```

---

## Production Best Practices

### 1. Separate Accounts

✅ **DO**:
- Use HTX MAIN account for trend strategy
- Keep sub-accounts for other strategies (arbitrage, etc.)
- Never run multiple strategies on same account

❌ **DON'T**:
- Mix trend and arbitrage on same account
- Run Java and Python services simultaneously
- Use isolated margin for trend (requires cross)

### 2. Gradual Rollout

1. **Backtest** (1-2 weeks): Verify determinism, test all scenarios
2. **PAPER** (14+ days): Real market, simulated orders
3. **LIVE small** (2-4 weeks): Start with minimal capital
4. **LIVE full**: Scale up gradually

### 3. Monitoring

**Hourly**:
- Health check
- Position count
- PnL

**Daily**:
- Review decisions
- Check circuit breaker status
- Verify account balance matches expectations

**Weekly**:
- Full performance review
- Compare to backtest expectations
- Adjust if needed (requires new approval)

---

## Troubleshooting

### Service won't start

```bash
# Check Python version
python --version  # Must be 3.11+

# Check dependencies
pip list | grep -E "pandas|numpy|httpx"

# Check config
python -c "import yaml; yaml.safe_load(open('configs/runs/production.yaml'))"

# Check account config
grep -A 5 "^account:" configs/runs/production.yaml
```

### Orders rejected

**Possible causes**:
1. Wrong account ID in config
2. Account guard blocking sub-account
3. No approval for current gate
4. Circuit breaker active

**Check**:
```bash
# Verify account config
python -c "from htx_trend.research.account_guard import AccountConfig; \
  config = AccountConfig('YOUR_ID', 'MAIN', 'cross'); \
  config.validate()"

# Check approvals
python -m htx_trend status --json | jq '.approvals'

# Check circuit breaker
python -m htx_trend status --json | jq '.circuit_breaker'
```

### Health check failing

```bash
# List components
ls heartbeats/

# Check stale heartbeat
python -m htx_trend health --max-age 300

# Manual check
cat heartbeats/backtest.json
```

---

## Support

**Documentation**:
- `RESEARCH_SYSTEM.md`: Complete usage guide
- `CHANGELOG.md`: Version history
- `MERGE_AND_PACKAGE_REPORT.md`: Release details

**Logs**:
- Run logs: `runs/<run_id>/run.log`
- Structured JSON format for parsing

**Contacts**:
- Risk/Approval: risk_officer@example.com
- Technical: dev_team@example.com
- On-call: oncall@example.com

---

**Deployment Date**: ___________  
**Deployed By**: ___________  
**Version**: 0.1.0  
**Commit**: 07c94e841faa153c1d66cbd90fee55f8c4f8dbdf
