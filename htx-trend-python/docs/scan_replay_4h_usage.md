# scan_replay 4H 支持使用说明

## 概述

`scan_replay.py` 现在支持参数化运行 `HTX_TREND_4H_V2` / `SWING_4H` 策略回放，用于 4H 周期的退出一致性验证。

## 命令行参数

### 新增参数

- `--strategy <STRATEGY>`: 策略版本（默认：`HTX_TREND_15M_V2`）
- `--profile <PROFILE>`: 时间周期配置（默认：`INTRADAY_15M`）
  - `INTRADAY_15M`: 15分钟周期（entry=15min, trend=60min）
  - `SWING_4H`: 4小时周期（entry=4hour, trend=1day）

### 原有参数

- `--inputs <PATH>`: 输入夹具目录（包含 MANIFEST.json、klines.csv、trades_closed_window.csv）
- `--out <PATH>`: 输出报告目录
- `--assumptions <PATH>`: 可选的假设参数覆盖 JSON 文件

## 使用示例

### 1. 运行 15m 回放（默认行为）

```bash
# 不加任何新参数，保持今日行为
python -m htx_trend.scan_replay \
    --inputs replay_inputs/20261021 \
    --out output/15m_replay
```

等价于显式指定：

```bash
python -m htx_trend.scan_replay \
    --inputs replay_inputs/20261021 \
    --out output/15m_replay \
    --strategy HTX_TREND_15M_V2 \
    --profile INTRADAY_15M
```

### 2. 运行 4H 回放（显式参数）

```bash
# 必须显式指定 4H 参数
python -m htx_trend.scan_replay \
    --inputs replay_inputs/4h_fixtures \
    --out output/4h_replay \
    --strategy HTX_TREND_4H_V2 \
    --profile SWING_4H
```

### 3. 带自定义假设的 4H 回放

```bash
python -m htx_trend.scan_replay \
    --inputs replay_inputs/4h_fixtures \
    --out output/4h_replay \
    --strategy HTX_TREND_4H_V2 \
    --profile SWING_4H \
    --assumptions custom_assumptions.json
```

`custom_assumptions.json` 示例：

```json
{
  "start_cst": "2026-10-27 00:00:00",
  "end_cst": "2026-11-02 12:00:00",
  "scan_delay_seconds": 30,
  "equity": 300,
  "leverage": 10
}
```

## 重要说明

### LOCKED_HASHES 分组

- **15m 路径**: 使用 `LOCKED_HASHES_BY_PROFILE['INTRADAY_15M']` 锁定哈希，保持原有夹具校验
- **4H 路径**: 使用 `LOCKED_HASHES_BY_PROFILE['SWING_4H']`（当前为空，信任 MANIFEST 声明哈希）
- **禁止**: 复用 `replay_inputs/20261021` 15m 夹具作为 4H 输入

### 夹具要求

4H 回放需要准备专门的 4H 夹具：

1. **MANIFEST.json**: 必须包含 `sha256` 字段声明文件哈希
2. **klines.csv**: 包含 `period=4hour` 和 `period=1day` 的 K 线数据
3. **trades_closed_window.csv**: 4H 策略的已平仓位参考数据
4. **optional_book (可选)**: 如需严格对齐 profit-at-risk 退出，提供盘口数据

### 行为差异

| 项目 | 15m (INTRADAY_15M) | 4H (SWING_4H) |
|-----|-------------------|---------------|
| entry_period | 15min (900s) | 4hour (14400s) |
| trend_period | 60min (3600s) | 1day (86400s) |
| 时钟步长 | 每 900 秒 | 每 14400 秒 |
| scan_delay 上界 | < 900s | < 14400s |
| 退出桶对齐 | // 900 | // 14400 |
| 报告文案 | "每15m" | "每4hour" |

### 验收标准

1. ✓ 显式 4H 参数可跑通至报告产出
2. ✓ 默认（无新参数）保持 15m 行为不变
3. ✓ LOCKED_HASHES 分组生效，15m/4H 互不误用
4. ✓ 报告中正确显示 profile、entry_period、trend_period

## 输出报告

### report.json 新增字段

```json
{
  "header": {
    "strategy": "HTX_TREND_4H_V2",
    "profile": "SWING_4H",
    "entry_period": "4hour",
    "trend_period": "1day",
    ...
  }
}
```

### report.md 动态描述

- 标题行会显示 `profile SWING_4H（entry=4hour, trend=1day）`
- 假设限制部分会显示 `每4hour调用一次`
- 夹具缺口描述会使用 `形成中 4hour bar`

## 测试验证

QA 团队运行 4H 回放后，应确认：

1. 两次运行产生逐字节一致的 `report.json` 和 `report.md`
2. `full_matches` 分数达到产品验收门槛（如 ≥11/12）
3. 无 `OUTSIDE_REPLAY_COVERAGE` 或 `EXIT_OUTSIDE_KLINE_COVERAGE` 错误
4. `optional_book` 状态与预期一致（有/无夹具）

## 限制与边界

- **本次交付**: 仅 PR 合入，不触发装机、热更、第5节
- **测试用途**: 合入后可用 tip/wheel/源码导入给测试
- **禁止**: 用于 LIVE/dual-live 热更，或设置 `EXIT_PARITY_APPROVED`
- **依赖**: 仍需 QA 提供完整 4H MANIFEST 夹具才能产出有效对照结果

## 示例完整命令（供 QA）

```bash
# 假设 QA 已准备 replay_inputs/s4-2-4h-baseline/ 夹具
cd /opt/coinstrategy/htx-trend-test

python3 -m htx_trend.scan_replay \
    --inputs replay_inputs/s4-2-4h-baseline \
    --out output/4h_run1 \
    --strategy HTX_TREND_4H_V2 \
    --profile SWING_4H

# 第二次运行
python3 -m htx_trend.scan_replay \
    --inputs replay_inputs/s4-2-4h-baseline \
    --out output/4h_run2 \
    --strategy HTX_TREND_4H_V2 \
    --profile SWING_4H

# 验证两次一致
diff output/4h_run1/report.json output/4h_run2/report.json
diff output/4h_run1/report.md output/4h_run2/report.md

# 检查分数
jq '.summary.full_matches, .summary.live_count' output/4h_run1/report.json
```

---

**编制**: 2026-11-02  
**用途**: S4-2 补 4H scan_replay CLI（产品口令 `d8d4d5138582b5622ccc63b9939534840adc90c87b4d42f2c0d17fae518e128a`）
