# 剩余必需工作清单

> **状态**: v0.1.0 清单项已实施（见 git log / REVIEW_CHECKLIST）  
> **目标版本**: v0.1.0  
> **更新时间**: 2026-10-11  
> **审查来源**: review-data-27e835e_fab6.md, review-prereg-27e835e_080d.md

## 数据层 (Data Layer)

### P0-3: 时间周期感知的恢复 + 跨批次间隙检测

**当前代码位置**:
- `src/htx_trend/research/collectors/continuous_collector.py` (采集器主体)
- `src/htx_trend/research/data/immutable_storage.py` (已有 `_parse_interval_seconds`)

**现状问题**:
1. 恢复逻辑不存在或硬编码为 4h
2. 间隙检测只在批次内部，不检测最后存储 bar 和新抓取第一个 bar 之间的间隙
3. 1day 时间周期可能无法正常工作

**设计要求**:
```python
class ContinuousCollector:
    def _load_resume_time(self, symbol: str, interval: str) -> datetime:
        """加载恢复时间（时间周期感知）
        
        逻辑:
        1. 读取状态文件中的 last_bar_time
        2. 根据 interval 计算下一个起始时间:
           - 4hour: last_bar_time + 4h
           - 1day: last_bar_time + 1d
        3. 如果无状态，默认从 90 天前开始
        """
        pass
    
    def _detect_cross_batch_gap(
        self,
        symbol: str,
        interval: str,
        new_batch: pd.DataFrame
    ) -> list[dict]:
        """检测跨批次间隙
        
        逻辑:
        1. 加载该 symbol+interval 的最后一个已存储 bar 的 bar_time
        2. 取新批次的第一个 bar_time
        3. 计算期望的下一个时间: last_stored + interval
        4. 如果 new_first_bar_time > expected_next + tolerance:
           - 记录到 gap_events.jsonl
           - 返回间隙详情
        """
        pass
```

**需要修改的文件**:
1. `src/htx_trend/research/collectors/continuous_collector.py`:
   - 添加 `_load_resume_time(symbol, interval)` 方法
   - 添加 `_detect_cross_batch_gap(symbol, interval, new_batch)` 方法
   - 修改 `run()` 循环调用间隙检测
   - 状态文件需要分 symbol+interval 存储

2. `src/htx_trend/research/data/immutable_storage.py`:
   - 添加 `get_latest_bar_time(symbol, interval)` 辅助方法

3. 新增 `gap_events.jsonl` 日志格式:
```json
{
  "timestamp": "2026-10-11T14:00:00Z",
  "symbol": "BTC-USDT",
  "interval": "4hour",
  "last_stored_bar_time": "2026-10-10T20:00:00Z",
  "expected_next": "2026-10-11T00:00:00Z",
  "actual_first_new": "2026-10-11T08:00:00Z",
  "gap_hours": 8.0,
  "missing_bars": 2
}
```

**必需测试** (创建 `tests/research/collectors/test_resume_and_gaps.py`):
1. `test_resume_1day_interval()`: 验证 1day 恢复计算正确
2. `test_resume_4hour_interval()`: 验证 4hour 恢复计算正确
3. `test_cross_batch_gap_detection()`: 最后存储 bar 到新批次第一个 bar 有间隙时检测到
4. `test_no_gap_continuous_batches()`: 连续批次不报告间隙
5. `test_gap_events_logged()`: 间隙事件写入 gap_events.jsonl

---

### P1-4: Dataset Manifest + Batch Hash 验证

**当前代码位置**:
- `src/htx_trend/research/data/immutable_storage.py` (批次存储)
- `src/htx_trend/research/data/dataset_manager.py` (数据集管理)
- `src/htx_trend/research/runner.py` (运行器)

**现状问题**:
1. 没有 DatasetManifest 类
2. 批次文件没有 SHA256 哈希记录
3. 运行不绑定 manifest
4. 加载时不验证哈希

**设计要求**:

**新文件**: `src/htx_trend/research/data/manifest.py`
```python
from dataclasses import dataclass
import hashlib

@dataclass
class BatchEntry:
    """批次条目"""
    symbol: str
    interval: str
    file_path: str  # 相对于存储根目录，如 "batches/BTC-USDT_4hour_20261011_120000.parquet"
    content_hash: str  # "sha256:abc123..."
    size_bytes: int
    bar_count: int

@dataclass
class DatasetManifest:
    """数据集清单"""
    dataset_id: str
    created_at: str  # ISO 8601
    batches: list[BatchEntry]
    metadata: dict
    
    @classmethod
    def generate_from_storage(cls, storage: ImmutableKlineStorage, dataset_id: str):
        """遍历存储生成清单
        
        1. 扫描 storage.base_path 下所有 .parquet 文件
        2. 计算每个文件的 SHA256
        3. 读取 bar 数量
        4. 创建 BatchEntry
        """
        pass
    
    def verify(self, storage: ImmutableKlineStorage) -> VerificationResult:
        """验证清单
        
        1. 对每个 BatchEntry，重新计算文件 SHA256
        2. 比较是否匹配
        3. 返回不匹配列表和缺失文件列表
        """
        pass
    
    def save(self, path: Path) -> None:
        """保存清单到 JSON"""
        pass
    
    @classmethod
    def load(cls, path: Path) -> "DatasetManifest":
        """从 JSON 加载清单"""
        pass

@dataclass
class VerificationResult:
    is_valid: bool
    mismatches: list[dict]  # [{"file": "...", "expected": "...", "actual": "..."}]
    missing_files: list[str]
```

**修改文件**:

1. `src/htx_trend/research/data/dataset_manager.py`:
```python
class DatasetManager:
    def freeze_dataset(self, dataset_id: str) -> DatasetManifest:
        """冻结数据集，生成清单"""
        manifest = DatasetManifest.generate_from_storage(
            self.storage, dataset_id
        )
        manifest_path = self.manifests_dir / f"{dataset_id}.json"
        manifest.save(manifest_path)
        return manifest
    
    def load_dataset_with_verification(self, dataset_id: str, manifest_hash: str):
        """加载数据集并验证清单
        
        1. 加载 manifest
        2. 验证哈希
        3. 加载数据
        """
        pass
```

2. `src/htx_trend/research/runner.py`:
```python
def _load_dataset(self):
    # 检查配置中是否绑定了 manifest_hash
    manifest_hash = self.config.get("manifest_hash")
    if manifest_hash:
        # 验证模式
        data = self.dataset_mgr.load_dataset_with_verification(
            dataset_id, manifest_hash
        )
    else:
        # 非验证模式（开发/训练）
        data = self.dataset_mgr.load_dataset(dataset_id)
```

3. `src/htx_trend/research/ledger/ledger.py`:
```python
# runs 表增加字段
CREATE TABLE IF NOT EXISTS runs (
    ...
    manifest_hash TEXT,  -- 绑定的 manifest SHA256
    ...
)
```

**必需测试** (创建 `tests/research/data/test_manifest.py`):
1. `test_generate_manifest()`: 生成清单包含所有批次
2. `test_manifest_includes_hashes()`: 每个批次有 SHA256
3. `test_verify_manifest_passes()`: 数据未改变时验证通过
4. `test_verify_manifest_fails_tampering()`: 篡改数据后验证失败
5. `test_manifest_save_load()`: 保存和加载清单
6. `test_runner_binds_manifest()`: 运行记录绑定 manifest_hash

---

### P1-6: HTX Kline API 参数验证

**当前代码位置**:
- `src/htx_trend/research/adapters/htx/market_data.py`

**现状问题**:
1. `fetch_klines_paginated` 可能缺少 `to` 参数
2. 没有错误处理
3. 没有根据 HTX 官方文档验证参数

**设计要求**:

**修改文件**: `src/htx_trend/research/adapters/htx/market_data.py`
```python
def fetch_klines_paginated(
    symbol: str,
    period: str,
    size: int = 2000,
    from_timestamp: int | None = None,
    to_timestamp: int | None = None,  # ✅ 必须支持
) -> pd.DataFrame:
    """分页抓取 K 线
    
    官方文档验证:
    - 参考: https://huobiapi.github.io/docs/spot/v1/en/#get-klines-candles
    - 参数: symbol, period, size (max 2000), from (optional), to (optional)
    - 错误码: https://huobiapi.github.io/docs/spot/v1/en/#error-code
    
    错误处理:
    - status != "ok" 时抛出 HtxAPIError
    - 解析 err-code 和 err-msg
    - 网络错误重试 3 次
    """
    params = {
        "symbol": symbol,
        "period": period,
        "size": min(size, 2000),  # 强制上限
    }
    
    if from_timestamp is not None:
        params["from"] = from_timestamp
    
    if to_timestamp is not None:  # ✅ 添加支持
        params["to"] = to_timestamp
    
    try:
        response = _make_request("/market/history/kline", params)
    except requests.RequestException as e:
        raise HtxAPIError(f"Network error: {e}")
    
    if response.get("status") != "ok":
        err_code = response.get("err-code", "unknown")
        err_msg = response.get("err-msg", "no message")
        raise HtxAPIError(f"HTX API error: {err_code} - {err_msg}")
    
    # ...解析数据...

class HtxAPIError(Exception):
    """HTX API 错误"""
    pass
```

**必需测试** (创建 `tests/research/adapters/test_htx_api_params.py`):
1. `test_fetch_klines_with_from_and_to()`: 验证 from/to 参数都传递
2. `test_fetch_klines_handles_api_error()`: status="error" 时抛出异常
3. `test_fetch_klines_parses_error_code()`: 解析 err-code 和 err-msg
4. `test_fetch_klines_respects_size_limit()`: size > 2000 时截断为 2000
5. `test_fetch_klines_network_error_handling()`: 网络错误抛出 HtxAPIError

---

## 预注册 (Pre-registration)

### Trial Records: 运行开始时写入 + finally 更新 + Triggers + Export CLI

**当前代码位置**:
- `src/htx_trend/research/runner.py:run()` (主运行方法)
- `src/htx_trend/research/ledger/ledger.py` (数据库 schema)
- `src/htx_trend/research/cli.py` (CLI 命令)

**现状问题**:
1. `trial_count` 写死为 1
2. trials 表有 schema 但从未插入数据
3. 没有 append-only triggers
4. 失败的运行不记录
5. 没有 export-trials 命令

**设计要求**:

**修改 Schema** (`src/htx_trend/research/ledger/ledger.py`):
```python
# trials 表结构
CREATE TABLE IF NOT EXISTS trials (
    trial_id TEXT PRIMARY KEY,  -- UUID
    run_id TEXT NOT NULL,
    parent_run_id TEXT,
    family_id TEXT NOT NULL,  -- 同一家族（从 baseline 开始）
    changed_key TEXT,  -- 哪个参数变了
    status TEXT NOT NULL,  -- 'started' | 'completed' | 'failed' | 'abandoned'
    started_at TEXT NOT NULL,
    completed_at TEXT,
    final_pnl REAL,
    final_sharpe REAL,
    error_message TEXT,
    FOREIGN KEY (run_id) REFERENCES runs(run_id)
)

-- ✅ Append-only triggers
CREATE TRIGGER IF NOT EXISTS trials_no_update
BEFORE UPDATE ON trials
FOR EACH ROW
BEGIN
    SELECT RAISE(ABORT, 'trials table is append-only, use status transitions via new rows');
END;

CREATE TRIGGER IF NOT EXISTS trials_no_delete
BEFORE DELETE ON trials
FOR EACH ROW
BEGIN
    SELECT RAISE(ABORT, 'trials table is append-only');
END;
```

**修改 Runner** (`src/htx_trend/research/runner.py`):
```python
def run(self) -> dict[str, Any]:
    # ✅ 运行开始时立即写入 trial
    trial_id = str(uuid.uuid4())
    family_id = self._determine_family_id()  # 从 parent_run_id 追溯到 baseline
    
    self.ledger.start_trial(
        trial_id=trial_id,
        run_id=self.run_id,
        parent_run_id=self.config.get("parent_run_id"),
        family_id=family_id,
        changed_key=self._get_changed_key(),  # 从单改验证获取
        status="started",
        started_at=datetime.now(timezone.utc),
    )
    
    try:
        # 原有运行逻辑
        self._validate_registration()
        data, frozen = self._load_dataset()
        results = self._run_backtest(data, frozen)
        report_path = self._generate_report(results)
        
        # ✅ 成功完成
        self.ledger.complete_trial(
            trial_id=trial_id,
            status="completed",
            completed_at=datetime.now(timezone.utc),
            final_pnl=results["final_equity"] - results["initial_equity"],
            final_sharpe=self._calculate_sharpe(results),
        )
        
        return results
    
    except Exception as e:
        # ✅ 失败时也记录
        self.ledger.complete_trial(
            trial_id=trial_id,
            status="failed",
            completed_at=datetime.now(timezone.utc),
            error_message=str(e),
        )
        raise
    
    finally:
        # ✅ 确保状态被更新（即使进程被杀）
        pass

def _determine_family_id(self) -> str:
    """追溯到 baseline 运行确定 family_id"""
    parent_id = self.config.get("parent_run_id")
    if not parent_id:
        # 这是 baseline
        return self.run_id
    
    # 递归查找
    current = parent_id
    while current:
        parent_run = self.ledger.get_run(current)
        if not parent_run or not parent_run.parent_run_id:
            return current
        current = parent_run.parent_run_id
    
    return parent_id
```

**新增 CLI 命令** (`src/htx_trend/research/cli.py`):
```python
@cli.command()
@click.option("--family-id", help="Filter by family_id")
@click.option("--format", type=click.Choice(["csv", "json"]), default="csv")
@click.option("--output", type=click.Path(), help="Output file path")
def export_trials(family_id, format, output):
    """导出试验记录供 DSR/PBO 计算
    
    输出格式 (CSV):
    trial_id,run_id,family_id,changed_key,status,started_at,completed_at,final_pnl,final_sharpe
    
    用途:
    - 计算 Deflated Sharpe Ratio (根据试验数调整)
    - 计算 Probability of Backtest Overfitting
    """
    ledger = ExperimentLedger(workspace / "ledger.db")
    
    if family_id:
        trials = ledger.get_trials_by_family(family_id)
    else:
        trials = ledger.get_all_trials()
    
    if format == "csv":
        # 写入 CSV
        pass
    else:
        # 写入 JSON
        pass
```

**必需测试** (创建 `tests/research/test_trial_records.py`):
1. `test_trial_written_at_start()`: run() 开始时 trials 表有记录
2. `test_trial_completed_on_success()`: 成功时 status='completed'
3. `test_trial_failed_on_exception()`: 异常时 status='failed'
4. `test_trial_no_update_trigger()`: UPDATE 被触发器拒绝
5. `test_trial_no_delete_trigger()`: DELETE 被触发器拒绝
6. `test_family_id_traces_to_baseline()`: family_id 正确追溯
7. `test_export_trials_csv()`: export-trials 输出有效 CSV
8. `test_trial_count_from_db()`: trial_count 从 DB 计数而非写死

---

### Test-Set Access: DatasetManager 强制 + Approval Gate + Access Log

**当前代码位置**:
- `src/htx_trend/research/data/dataset.py` (数据集类)
- `src/htx_trend/research/data/dataset_manager.py` (数据集管理器)
- `src/htx_trend/research/runner.py:_load_dataset()` (加载逻辑)
- `src/htx_trend/research/ledger/ledger.py` (审批表)

**现状问题**:
1. `stage_acceptance` 是 YAML 自填布尔值，无审批门禁
2. 测试集访问在 runner 层切片，DatasetManager 可以直接返回测试集
3. 没有 holdout_access_log 记录每次读取
4. split 名称错误不会报错（fallback 到测试集）

**设计要求**:

**修改 Schema** (`src/htx_trend/research/ledger/ledger.py`):
```python
# 新表：holdout_access_log
CREATE TABLE IF NOT EXISTS holdout_access_log (
    access_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    dataset_id TEXT NOT NULL,
    dataset_hash TEXT NOT NULL,
    approval_id TEXT NOT NULL,  -- 对应 approvals.approval_id
    accessed_at TEXT NOT NULL,
    split_requested TEXT NOT NULL,  -- 明确记录请求的 split
    FOREIGN KEY (run_id) REFERENCES runs(run_id),
    FOREIGN KEY (approval_id) REFERENCES approvals(approval_id)
)

-- 同一 dataset_hash 只能读取一次（或需要新审批）
CREATE UNIQUE INDEX IF NOT EXISTS idx_holdout_one_access_per_dataset
ON holdout_access_log(dataset_hash, approval_id);
```

**修改 DatasetManager** (`src/htx_trend/research/data/dataset_manager.py`):
```python
class DatasetManager:
    def load_dataset(
        self,
        dataset_id: str,
        symbols: list[str],
        split: str = "train",  # ✅ 明确的 split 参数
        approval_id: str | None = None,  # ✅ 测试集需要审批 ID
    ) -> tuple[dict[str, pd.DataFrame], Any]:
        """加载数据集
        
        规则:
        1. split 必须是 'train', 'val', 'test' 之一，否则 raise ValueError
        2. split='test' 必须提供 approval_id
        3. approval_id 必须是 APPROVED 状态的 STAGE_ACCEPTANCE 审批
        4. 检查该 dataset_hash 是否已被访问过
        5. 记录到 holdout_access_log
        6. 只返回指定 split 的数据（不返回全量）
        """
        # ✅ 验证 split 名称
        if split not in ["train", "val", "test"]:
            raise ValueError(
                f"Invalid split name: {split}. "
                f"Must be one of: train, val, test"
            )
        
        # ✅ 测试集访问控制
        if split == "test":
            if not approval_id:
                raise ValueError(
                    "Test set access requires an approval_id. "
                    "Run must have an APPROVED STAGE_ACCEPTANCE approval."
                )
            
            # 验证审批
            approval = self.ledger.get_approval(approval_id)
            if not approval:
                raise ValueError(f"Approval {approval_id} not found")
            
            if approval.gate != "STAGE_ACCEPTANCE":
                raise ValueError(
                    f"Test set requires STAGE_ACCEPTANCE approval, "
                    f"got {approval.gate}"
                )
            
            if approval.decision != "APPROVED":
                raise ValueError(
                    f"Approval {approval_id} is {approval.decision}, "
                    f"not APPROVED"
                )
            
            # 检查是否已访问过该 dataset
            dataset_hash = self._compute_dataset_hash(dataset_id)
            if self.ledger.is_holdout_accessed(dataset_hash, approval_id):
                raise ValueError(
                    f"Dataset {dataset_id} (hash {dataset_hash}) "
                    f"has already been accessed with this approval. "
                    f"Test set can only be used once per approval."
                )
            
            # ✅ 记录访问
            self.ledger.log_holdout_access(
                run_id=self.current_run_id,
                dataset_id=dataset_id,
                dataset_hash=dataset_hash,
                approval_id=approval_id,
                accessed_at=datetime.now(timezone.utc),
                split_requested=split,
            )
        
        # 加载数据
        frozen = self._load_frozen_dataset(dataset_id)
        
        # ✅ 只返回指定 split
        start_idx, end_idx = frozen.get_split(split)
        
        data = {}
        for symbol in symbols:
            df = frozen.load_symbol_data(symbol)
            data[symbol] = df.iloc[start_idx:end_idx].copy()
        
        return data, frozen
```

**修改 Runner** (`src/htx_trend/research/runner.py`):
```python
def _load_dataset(self):
    dataset_id = self.config["dataset_id"]
    symbols = self.config.get("symbols", ["BTC-USDT", "ETH-USDT"])
    split = self.config.get("split", "train")
    
    # ✅ 测试集需要审批
    approval_id = None
    if split == "test":
        # 从 config 或 ledger 获取审批 ID
        approval_id = self.config.get("stage_acceptance_approval_id")
        if not approval_id:
            raise ValueError(
                "Test set access requires stage_acceptance_approval_id in config. "
                "Obtain approval first via CLI: approve --gate STAGE_ACCEPTANCE"
            )
    
    # ✅ DatasetManager 负责所有访问控制
    data, frozen = self.dataset_mgr.load_dataset(
        dataset_id=dataset_id,
        symbols=symbols,
        split=split,
        approval_id=approval_id,
    )
    
    # 不再需要 runner 层切片，DatasetManager 已经返回正确的 split
    return data, frozen
```

**必需测试** (创建 `tests/research/test_holdout_access.py`):
1. `test_invalid_split_name_raises()`: split='unknown' 抛出异常
2. `test_test_split_requires_approval_id()`: split='test' 无 approval_id 时报错
3. `test_test_split_requires_stage_acceptance()`: 其他类型审批被拒绝
4. `test_test_split_requires_approved_status()`: REJECTED 审批被拒绝
5. `test_holdout_access_logged()`: 测试集访问写入 holdout_access_log
6. `test_same_dataset_hash_second_access_blocked()`: 同一数据集第二次访问被阻止
7. `test_train_val_no_approval_needed()`: train/val 不需要审批
8. `test_dataset_manager_returns_only_requested_split()`: 只返回指定 split 的数据

---

### Report Fields: evidence_ref/conclusion/decision + Real Parent Diffs

**当前代码位置**:
- `src/htx_trend/research/reports/report_generator.py`
- `src/htx_trend/research/runner.py:_generate_report()`
- `src/htx_trend/research/ledger/ledger.py` (registrations 表)

**现状问题**:
1. 报告没有 evidence_ref/conclusion/decision 字段
2. 父运行对比的 PnL 写死为 0
3. 没有展示实际的配置差异

**设计要求**:

**修改 ReportGenerator** (`src/htx_trend/research/reports/report_generator.py`):
```python
class ReportGenerator:
    def generate_report(
        self,
        config: dict,
        fills: list,
        decisions: pd.DataFrame,
        parent_comparison: dict | None = None,
        registration: ExperimentRegistration | None = None,  # ✅ 新增
    ) -> Path:
        """生成报告
        
        新增章节:
        1. Experiment Registration
           - change
           - hypothesis
           - expected_effect
           - evidence_ref (带链接)
           - keep_rule / drop_rule
           - trial_count
           - uses_holdout
        
        2. Conclusion & Decision (运行后填写)
           - conclusion (从 ledger 读取)
           - decision (KEEP / DROP / INCONCLUSIVE)
        
        3. Parent Run Comparison (真实数据)
           - Parent: {parent_run_id}
           - Changed parameter: {changed_key}
           - Current PnL: {actual_current_pnl}
           - Parent PnL: {actual_parent_pnl}  # ✅ 从 ledger 读取
           - Difference: {diff}
           - Percentage change: {pct_change}
        """
        report = {
            "run_id": config["run_id"],
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }
        
        # ✅ 实验登记章节
        if registration:
            report["registration"] = {
                "change": registration.change,
                "hypothesis": registration.hypothesis,
                "expected_effect": registration.expected_effect,
                "evidence_ref": registration.evidence_ref,
                "keep_rule": registration.keep_rule,
                "drop_rule": registration.drop_rule,
                "trial_count": registration.trial_count,
                "uses_holdout": registration.uses_holdout,
            }
        
        # ✅ 结论与决策（运行后填写）
        report["conclusion_and_decision"] = {
            "conclusion": registration.conclusion if registration else None,
            "decision": registration.decision if registration else None,
            "note": "Fill via CLI: update-conclusion --run-id {run_id}",
        }
        
        # ✅ 父运行对比（真实数据）
        if parent_comparison:
            report["parent_comparison"] = parent_comparison
        
        # ...其他章节...
        
        return report_path
```

**修改 Runner** (`src/htx_trend/research/runner.py`):
```python
def _generate_report(self, results: dict[str, Any]) -> Path:
    # ...
    
    # ✅ 获取登记信息
    registration = self.ledger.get_registration(self.run_id)
    
    # ✅ 真实的父运行对比
    parent_comparison = None
    parent_run_id = self.config.get("parent_run_id")
    if parent_run_id:
        parent_run = self.ledger.get_run(parent_run_id)
        if parent_run and parent_run.metrics:
            current_pnl = results["final_equity"] - results["initial_equity"]
            parent_pnl = parent_run.metrics.get("total_pnl", 0)
            
            parent_comparison = {
                "parent_run_id": parent_run_id,
                "changed_key": self._get_changed_key(),
                "current_pnl": current_pnl,
                "parent_pnl": parent_pnl,  # ✅ 真实数据
                "pnl_diff": current_pnl - parent_pnl,
                "pct_change": ((current_pnl - parent_pnl) / abs(parent_pnl) * 100) 
                              if parent_pnl != 0 else None,
            }
    
    return generator.generate_report(
        config=self.config,
        fills=fills_data,
        decisions=decisions_df,
        parent_comparison=parent_comparison,
        registration=registration,  # ✅ 传递登记信息
    )
```

**新增 CLI 命令** (`src/htx_trend/research/cli.py`):
```python
@cli.command()
@click.option("--run-id", required=True)
@click.option("--conclusion", required=True, help="Human-written conclusion")
@click.option("--decision", required=True, type=click.Choice(["KEEP", "DROP", "INCONCLUSIVE"]))
def update_conclusion(run_id, conclusion, decision):
    """更新运行的结论和决策
    
    结论和决策只能追加，不能修改：
    - 在 registrations 表追加新事件行
    - 或使用单独的 conclusions 表
    """
    ledger = ExperimentLedger(workspace / "ledger.db")
    ledger.update_conclusion(
        run_id=run_id,
        conclusion=conclusion,
        decision=decision,
        updated_at=datetime.now(timezone.utc),
    )
    click.echo(f"✓ Updated conclusion for {run_id}")
```

**必需测试** (创建 `tests/research/test_report_fields.py`):
1. `test_report_includes_registration_fields()`: 报告包含 evidence_ref/change/hypothesis 等
2. `test_report_includes_conclusion_decision()`: 报告包含 conclusion/decision
3. `test_parent_comparison_real_pnl()`: 父运行 PnL 从 ledger 读取，非 0
4. `test_parent_comparison_calculates_diff()`: 正确计算差异和百分比
5. `test_update_conclusion_cli()`: update-conclusion 命令更新数据库

---

## 运维 (Operations)

### Health Check: Bar Staleness Per Timeframe

**当前代码位置**:
- `src/htx_trend/research/ops/heartbeat.py`
- `src/htx_trend/research/cli.py:health()`

**现状问题**:
1. health check 只检查 heartbeat 进程时间戳
2. 不检查 last_processed_bar_time 是否过期
3. 不区分不同 timeframe 的预期更新频率

**设计要求**:

**修改 Heartbeat** (`src/htx_trend/research/ops/heartbeat.py`):
```python
from dataclasses import dataclass

@dataclass
class BarStalenessCheck:
    """K线数据新鲜度检查"""
    symbol: str
    interval: str
    last_bar_time: datetime
    expected_next_close: datetime
    lag_minutes: float
    is_stale: bool
    threshold_minutes: float

def check_health(
    max_heartbeat_age_seconds: int = 300,
    bar_staleness_threshold_minutes: int = 10,
) -> tuple[bool, dict]:
    """健康检查
    
    检查项:
    1. Heartbeat 进程时间戳 (< max_heartbeat_age_seconds)
    2. 每个 symbol+interval 的 last_processed_bar_time 新鲜度
    
    Bar staleness 计算:
    - 获取 last_processed_bar_time
    - 计算期望的下一个关闭时间: last_bar_time + interval
    - 如果 now > expected_next_close + threshold:
        标记为 stale
    
    返回:
    - is_healthy: bool
    - details: {
        "heartbeat": {...},
        "bar_staleness": [BarStalenessCheck, ...],
      }
    """
    is_healthy = True
    details = {}
    
    # 1. Heartbeat 检查
    heartbeat_ok = _check_heartbeat_age(max_heartbeat_age_seconds)
    details["heartbeat"] = {
        "status": "ok" if heartbeat_ok else "stale",
        "last_heartbeat": ...,
    }
    
    if not heartbeat_ok:
        is_healthy = False
    
    # 2. Bar staleness 检查
    bar_checks = []
    state = _load_state()  # 读取采集器状态
    
    for symbol_interval, info in state.items():
        symbol, interval = symbol_interval.split("_")
        last_bar_time = datetime.fromisoformat(info["last_bar_time"])
        
        interval_seconds = _parse_interval_seconds(interval)
        expected_next_close = last_bar_time + timedelta(seconds=interval_seconds)
        now = datetime.now(timezone.utc)
        
        lag_minutes = (now - expected_next_close).total_seconds() / 60
        is_stale = lag_minutes > bar_staleness_threshold_minutes
        
        check = BarStalenessCheck(
            symbol=symbol,
            interval=interval,
            last_bar_time=last_bar_time,
            expected_next_close=expected_next_close,
            lag_minutes=lag_minutes,
            is_stale=is_stale,
            threshold_minutes=bar_staleness_threshold_minutes,
        )
        
        bar_checks.append(check)
        
        if is_stale:
            is_healthy = False
    
    details["bar_staleness"] = [asdict(c) for c in bar_checks]
    
    return is_healthy, details
```

**修改 CLI** (`src/htx_trend/research/cli.py`):
```python
@cli.command()
@click.option("--threshold-minutes", default=10, help="Bar staleness threshold")
def health(threshold_minutes):
    """健康检查
    
    退出码:
    - 0: 健康
    - 1: 不健康（heartbeat 过期或 bar 过期）
    """
    is_healthy, details = check_health(
        bar_staleness_threshold_minutes=threshold_minutes
    )
    
    if is_healthy:
        click.echo("✓ Healthy")
        sys.exit(0)
    else:
        click.echo("✗ Unhealthy")
        click.echo(json.dumps(details, indent=2))
        sys.exit(1)
```

**必需测试** (创建 `tests/research/ops/test_bar_staleness.py`):
1. `test_health_check_fresh_bars()`: 最新 bar 在阈值内，返回 healthy
2. `test_health_check_stale_4hour_bar()`: 4hour bar 超过阈值，返回 unhealthy
3. `test_health_check_stale_1day_bar()`: 1day bar 超过阈值，返回 unhealthy
4. `test_health_cli_exit_code_healthy()`: health 命令健康时退出码 0
5. `test_health_cli_exit_code_unhealthy()`: health 命令不健康时退出码 1
6. `test_bar_staleness_per_timeframe()`: 不同 timeframe 独立检查

---

### Unified Systemd Setup

**当前代码位置**:
- `systemd/` 目录（可能有多个不一致的 unit 文件）
- `docs/DEPLOYMENT.md` (部署文档)

**现状问题**:
1. 可能有多个 systemd unit 文件
2. 路径、用户、权限不统一
3. 没有明确的部署目录和用户

**设计要求**:

**标准部署配置**:
```
部署目录: /opt/coinstrategy/htx-trend-py
用户: ubuntu (可配置，在文档中说明)
数据目录: /opt/coinstrategy/htx-trend-py/data
日志目录: /opt/coinstrategy/htx-trend-py/logs
状态文件: /opt/coinstrategy/htx-trend-py/status.json
Heartbeat: /opt/coinstrategy/htx-trend-py/heartbeat.txt
```

**Systemd Unit 文件** (创建 `systemd/htx-trend-collector.service`):
```ini
[Unit]
Description=HTX Trend Research Kline Collector
After=network.target

[Service]
Type=simple
User=ubuntu
Group=ubuntu
WorkingDirectory=/opt/coinstrategy/htx-trend-py
Environment="PYTHONPATH=/opt/coinstrategy/htx-trend-py/src"

# ✅ 明确的读写路径
ReadWritePaths=/opt/coinstrategy/htx-trend-py/data
ReadWritePaths=/opt/coinstrategy/htx-trend-py/logs
ReadWritePaths=/opt/coinstrategy/htx-trend-py/status.json
ReadWritePaths=/opt/coinstrategy/htx-trend-py/heartbeat.txt
ReadWritePaths=/opt/coinstrategy/htx-trend-py/collector_state.json

ExecStart=/usr/bin/python3 -m htx_trend.research.collectors.continuous_collector \
    --config /opt/coinstrategy/htx-trend-py/configs/collector.yaml

Restart=always
RestartSec=10

StandardOutput=journal
StandardError=journal
SyslogIdentifier=htx-trend-collector

[Install]
WantedBy=multi-user.target
```

**Systemd Unit 文件** (创建 `systemd/htx-trend-heartbeat.service`):
```ini
[Unit]
Description=HTX Trend Research Heartbeat Monitor
After=network.target

[Service]
Type=simple
User=ubuntu
Group=ubuntu
WorkingDirectory=/opt/coinstrategy/htx-trend-py

ExecStart=/usr/bin/python3 -m htx_trend.research.ops.heartbeat \
    --interval 60 \
    --file /opt/coinstrategy/htx-trend-py/heartbeat.txt

Restart=always
RestartSec=5

StandardOutput=journal
StandardError=journal
SyslogIdentifier=htx-trend-heartbeat

[Install]
WantedBy=multi-user.target
```

**部署文档** (更新 `docs/DEPLOYMENT.md`):
```markdown
# 部署指南

## 标准部署路径

- **部署目录**: `/opt/coinstrategy/htx-trend-py`
- **用户**: `ubuntu` (可配置)
- **数据目录**: `/opt/coinstrategy/htx-trend-py/data`
- **日志目录**: `/opt/coinstrategy/htx-trend-py/logs`

## 部署步骤

1. 创建部署目录:
```bash
sudo mkdir -p /opt/coinstrategy/htx-trend-py
sudo chown ubuntu:ubuntu /opt/coinstrategy/htx-trend-py
```

2. 部署代码:
```bash
cd /opt/coinstrategy/htx-trend-py
# 拷贝 src/, configs/, systemd/ 等
```

3. 安装 systemd units:
```bash
sudo cp systemd/*.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable htx-trend-collector htx-trend-heartbeat
sudo systemctl start htx-trend-collector htx-trend-heartbeat
```

4. 验证:
```bash
systemctl status htx-trend-collector
systemctl status htx-trend-heartbeat
```

## 自定义用户

如需使用非 `ubuntu` 用户:
1. 修改 systemd unit 中的 `User=` 和 `Group=`
2. 修改部署目录权限
3. 重新加载: `sudo systemctl daemon-reload`
```

**需要的文件**:
1. `systemd/htx-trend-collector.service` (新建或覆盖)
2. `systemd/htx-trend-heartbeat.service` (新建或覆盖)
3. `docs/DEPLOYMENT.md` (更新部署路径和用户)
4. 移除任何不一致的旧 unit 文件

**验证清单** (不需要自动化测试，但需要手动验证):
- [ ] systemd units 使用统一的部署路径
- [ ] User=ubuntu, Group=ubuntu
- [ ] ReadWritePaths 包含所有需要写入的路径
- [ ] WorkingDirectory 正确
- [ ] 文档中明确说明如何自定义用户

---

### Collector Config: Symbols/Timeframes from Config

**当前代码位置**:
- `src/htx_trend/research/collectors/continuous_collector.py`
- 可能有一个配置文件位置，但 symbols/intervals 可能是硬编码的

**现状问题**:
1. Symbols 可能硬编码（或只有 BTC-USDT）
2. Intervals 可能硬编码（或只有 4hour）
3. 没有配置文件或配置文件不完整

**设计要求**:

**配置文件** (创建 `configs/collector.yaml`):
```yaml
collector:
  # ✅ 从配置读取 symbols 和 intervals
  symbols:
    - BTC-USDT
    - ETH-USDT
  
  intervals:
    - 4hour
    - 1day  # ✅ 必须支持 1day
  
  poll_interval_seconds: 300  # 每 5 分钟检查一次
  
  storage:
    base_path: /opt/coinstrategy/htx-trend-py/data/klines
  
  state:
    file_path: /opt/coinstrategy/htx-trend-py/collector_state.json
  
  htx:
    api_base_url: https://api.huobi.pro
    rate_limit_requests_per_second: 10
```

**修改 Collector** (`src/htx_trend/research/collectors/continuous_collector.py`):
```python
class ContinuousCollector:
    def __init__(self, config_path: Path):
        """从配置文件初始化采集器
        
        配置必须包含:
        - collector.symbols (list)
        - collector.intervals (list)
        - collector.storage.base_path
        - collector.state.file_path
        """
        with open(config_path) as f:
            config = yaml.safe_load(f)
        
        collector_config = config.get("collector", {})
        
        # ✅ 从配置读取
        self.symbols = collector_config.get("symbols")
        self.intervals = collector_config.get("intervals")
        
        if not self.symbols:
            raise ValueError("Config missing 'collector.symbols'")
        
        if not self.intervals:
            raise ValueError("Config missing 'collector.intervals'")
        
        # ✅ 验证包含 1day
        if "1day" not in self.intervals and "1d" not in self.intervals:
            raise ValueError(
                "Collector must support 1day interval. "
                "Add '1day' to collector.intervals in config."
            )
        
        self.poll_interval = collector_config.get("poll_interval_seconds", 300)
        
        storage_config = collector_config.get("storage", {})
        self.storage = ImmutableKlineStorage(
            Path(storage_config.get("base_path", "./data/klines"))
        )
        
        state_config = collector_config.get("state", {})
        self.state_file = Path(
            state_config.get("file_path", "./collector_state.json")
        )
    
    def run(self):
        """主循环
        
        对每个 (symbol, interval) 组合:
        1. 加载恢复时间（时间周期感知）
        2. 抓取新数据
        3. 过滤已关闭的 bar
        4. 检测跨批次间隙
        5. 检测修订
        6. 存储
        7. 更新状态
        """
        while True:
            for symbol in self.symbols:
                for interval in self.intervals:
                    self._collect_one(symbol, interval)
            
            time.sleep(self.poll_interval)
```

**修改 Systemd Unit** (更新 `systemd/htx-trend-collector.service`):
```ini
ExecStart=/usr/bin/python3 -m htx_trend.research.collectors.continuous_collector \
    --config /opt/coinstrategy/htx-trend-py/configs/collector.yaml
```

**必需测试** (创建 `tests/research/collectors/test_collector_config.py`):
1. `test_collector_loads_symbols_from_config()`: symbols 从 YAML 读取
2. `test_collector_loads_intervals_from_config()`: intervals 从 YAML 读取
3. `test_collector_requires_1day_interval()`: 配置缺少 1day 时报错
4. `test_collector_missing_symbols_raises()`: 配置缺少 symbols 时报错
5. `test_collector_uses_configured_paths()`: storage/state 路径从配置读取

---

## C02 测试

### C02: Favorable-Only Ratchet Test

**当前代码位置**:
- `src/htx_trend/research/conditions/c02_atr_stop.py` (已重写为 trailing stop)

**现状问题**:
1. 代码已实现 trailing stop 逻辑
2. 但没有测试验证"只朝有利方向移动"

**设计要求**:

**必需测试** (创建 `tests/research/conditions/test_c02_trailing_stop.py`):
```python
"""测试 C02 止损只朝有利方向移动"""

def test_c02_long_stop_only_moves_up():
    """测试多头止损只能上移，不能下移"""
    c02 = C02ATRStop(atr_period=14, atr_multiplier=2.0)
    
    # 模拟持仓
    position_id = "LONG_BTC"
    entry_price = 100.0
    initial_atr = 5.0
    initial_stop = entry_price - (initial_atr * 2.0)  # 90.0
    
    # 第一根 bar: 入场，设置初始止损
    bar1 = {
        "close": 100.0,
        "high": 101.0,
        "low": 99.0,
        "atr14": initial_atr,
    }
    
    ctx1 = DecisionContext(
        symbol="BTC-USDT",
        bar_time=datetime.now(timezone.utc),
        df=None,
        current_idx=0,
        current_position={"entry_price": entry_price, "side": "LONG", "position_id": position_id},
    )
    
    result1 = c02.evaluate(ctx1, {})
    # 初始止损应该被设置
    assert position_id in c02._position_stops
    assert c02._position_stops[position_id] == initial_stop
    
    # 第二根 bar: 价格上涨，ATR 缩小，按新 ATR 计算的止损会更低
    bar2 = {
        "close": 110.0,  # 价格上涨
        "high": 111.0,
        "low": 109.0,
        "atr14": 3.0,  # ATR 缩小
    }
    
    new_stop_if_recalculated = 110.0 - (3.0 * 2.0)  # 104.0
    
    ctx2 = DecisionContext(
        symbol="BTC-USDT",
        bar_time=datetime.now(timezone.utc) + timedelta(hours=4),
        df=None,
        current_idx=1,
        current_position={"entry_price": entry_price, "side": "LONG", "position_id": position_id},
    )
    ctx2.get_current_row = lambda: bar2
    
    result2 = c02.evaluate(ctx2, {})
    
    # ✅ 关键测试：止损应该上移到 104.0（基于当前价格和 ATR）
    # 而不是保持在初始的 90.0，也不应该下移
    current_stop = c02._position_stops[position_id]
    assert current_stop == new_stop_if_recalculated  # 104.0
    assert current_stop > initial_stop  # 确保上移了
    
    # 第三根 bar: 价格回调，ATR 扩大，按新 ATR 计算的止损会更低
    bar3 = {
        "close": 108.0,  # 价格回调
        "high": 110.0,
        "low": 107.0,
        "atr14": 6.0,  # ATR 扩大
    }
    
    new_stop_if_recalculated_bar3 = 108.0 - (6.0 * 2.0)  # 96.0 (更低！)
    
    ctx3 = DecisionContext(
        symbol="BTC-USDT",
        bar_time=datetime.now(timezone.utc) + timedelta(hours=8),
        df=None,
        current_idx=2,
        current_position={"entry_price": entry_price, "side": "LONG", "position_id": position_id},
    )
    ctx3.get_current_row = lambda: bar3
    
    result3 = c02.evaluate(ctx3, {})
    
    # ✅ 关键测试：止损不应该下移！应该保持在 104.0
    current_stop_after_bar3 = c02._position_stops[position_id]
    assert current_stop_after_bar3 == new_stop_if_recalculated  # 仍然是 104.0
    assert current_stop_after_bar3 > new_stop_if_recalculated_bar3  # 没有下移到 96.0


def test_c02_short_stop_only_moves_down():
    """测试空头止损只能下移，不能上移"""
    c02 = C02ATRStop(atr_period=14, atr_multiplier=2.0)
    
    position_id = "SHORT_BTC"
    entry_price = 100.0
    initial_atr = 5.0
    initial_stop = entry_price + (initial_atr * 2.0)  # 110.0
    
    # Bar 1: 入场
    bar1 = {
        "close": 100.0,
        "high": 101.0,
        "low": 99.0,
        "atr14": initial_atr,
    }
    
    ctx1 = DecisionContext(
        symbol="BTC-USDT",
        bar_time=datetime.now(timezone.utc),
        df=None,
        current_idx=0,
        current_position={"entry_price": entry_price, "side": "SHORT", "position_id": position_id},
    )
    ctx1.get_current_row = lambda: bar1
    
    result1 = c02.evaluate(ctx1, {})
    assert c02._position_stops[position_id] == initial_stop
    
    # Bar 2: 价格下跌（有利），止损应该下移
    bar2 = {
        "close": 90.0,
        "high": 91.0,
        "low": 89.0,
        "atr14": 3.0,
    }
    
    new_stop_bar2 = 90.0 + (3.0 * 2.0)  # 96.0 (更低，有利)
    
    ctx2 = DecisionContext(
        symbol="BTC-USDT",
        bar_time=datetime.now(timezone.utc) + timedelta(hours=4),
        df=None,
        current_idx=1,
        current_position={"entry_price": entry_price, "side": "SHORT", "position_id": position_id},
    )
    ctx2.get_current_row = lambda: bar2
    
    result2 = c02.evaluate(ctx2, {})
    
    # ✅ 止损应该下移到 96.0
    assert c02._position_stops[position_id] == new_stop_bar2
    assert c02._position_stops[position_id] < initial_stop
    
    # Bar 3: 价格反弹（不利），止损不应该上移
    bar3 = {
        "close": 95.0,
        "high": 96.0,
        "low": 94.0,
        "atr14": 6.0,
    }
    
    new_stop_if_recalculated_bar3 = 95.0 + (6.0 * 2.0)  # 107.0 (更高，不利！)
    
    ctx3 = DecisionContext(
        symbol="BTC-USDT",
        bar_time=datetime.now(timezone.utc) + timedelta(hours=8),
        df=None,
        current_idx=2,
        current_position={"entry_price": entry_price, "side": "SHORT", "position_id": position_id},
    )
    ctx3.get_current_row = lambda: bar3
    
    result3 = c02.evaluate(ctx3, {})
    
    # ✅ 止损不应该上移！应该保持在 96.0
    assert c02._position_stops[position_id] == new_stop_bar2  # 仍然是 96.0
    assert c02._position_stops[position_id] < new_stop_if_recalculated_bar3  # 没有上移到 107.0


def test_c02_stop_initialized_at_entry():
    """测试止损在入场时被初始化"""
    c02 = C02ATRStop(atr_period=14, atr_multiplier=2.0)
    
    # 第一次调用 evaluate，position_id 不在 _position_stops 中
    position_id = "NEW_POSITION"
    entry_price = 100.0
    atr = 5.0
    
    bar = {
        "close": 100.0,
        "high": 101.0,
        "low": 99.0,
        "atr14": atr,
    }
    
    ctx = DecisionContext(
        symbol="BTC-USDT",
        bar_time=datetime.now(timezone.utc),
        df=None,
        current_idx=0,
        current_position={"entry_price": entry_price, "side": "LONG", "position_id": position_id},
    )
    ctx.get_current_row = lambda: bar
    
    # 入场前不应该有止损记录
    assert position_id not in c02._position_stops
    
    result = c02.evaluate(ctx, {})
    
    # 入场后应该设置初始止损
    assert position_id in c02._position_stops
    expected_initial_stop = entry_price - (atr * 2.0)
    assert c02._position_stops[position_id] == expected_initial_stop
```

**测试文件**: `tests/research/conditions/test_c02_trailing_stop.py` (3个测试)

---

## 总结

### 需要完成的项目（按优先级）

| 项目 | 文件数 | 测试数 | 预估工作量 |
|------|--------|--------|-----------|
| **数据层 P0-3**: 恢复+间隙 | 2 修改 | 5 测试 | 1-2h |
| **数据层 P1-4**: Manifest | 1 新建, 3 修改 | 6 测试 | 2-3h |
| **数据层 P1-6**: HTX API | 1 修改 | 5 测试 | 1h |
| **预注册**: Trial records | 3 修改, triggers | 8 测试 | 2-3h |
| **预注册**: Test-set gate | 2 修改, schema | 8 测试 | 2-3h |
| **预注册**: Report fields | 2 修改, CLI | 5 测试 | 1-2h |
| **Ops**: Bar staleness | 2 修改 | 6 测试 | 1-2h |
| **Ops**: Systemd | 2 新建, docs | 手动验证 | 1h |
| **Ops**: Collector config | 1 修改, 1 新建 | 5 测试 | 1h |
| **C02**: Trailing test | 0 修改 | 3 测试 | 30min |
| **总计** | **~20 文件** | **~54 测试** | **12-18小时** |

### 当前状态

**已完成**:
- ✅ P0-1, P0-2 (数据层)
- ✅ P0 登记门禁完整
- ✅ P1 C02 重写 + 默认值移除
- ✅ P1-5 原子状态 (带测试)

**待完成** (本文档详细说明):
- ❌ Data: P0-3, P1-4, P1-6
- ❌ Prereg: 全部3项
- ❌ Ops: 全部3项
- ❌ C02: favorable-only 测试

每个待完成项都包含：
1. **当前代码位置**
2. **现状问题**
3. **完整设计**（包括代码示例）
4. **需要修改的文件**
5. **必需测试**（包括测试名称和验证点）

这份文档可以直接交给另一个 agent 实施。
