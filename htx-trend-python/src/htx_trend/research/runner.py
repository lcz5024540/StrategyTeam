"""核心回测运行器"""

from __future__ import annotations

import hashlib
import subprocess
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pandas as pd
import yaml

from .adapters.htx.fees import create_fee_provider
from .conditions import C01DonchianEnsemble, C02ATRStop, DecisionContext
from .data import DatasetManager
from .engine.backtest import CostScenario, SimBroker
from .engine.interfaces import Order
from .features import compute_all_features
from .context import compute_regime_labels
from .ledger import ExperimentRegistration, LedgerManager, RunRecord
from .ops import log_event, LogComponent, LogLevel, set_run_id, setup_logging
from .reports import ReportGenerator
from .risk import LiquidationChecker, PositionSizer
from .strategy import Position, StateMachine, StrategyState


class BacktestRunner:
    def __init__(self, config_path: Path, workspace_dir: Path):
        self.config_path = config_path
        self.workspace_dir = workspace_dir
        
        with open(config_path) as f:
            self.config = yaml.safe_load(f)
        
        self.run_id = self.config["run_id"]
        set_run_id(self.run_id)
        
        setup_logging(
            level=LogLevel.INFO,
            json_output=True,
            log_file=str(workspace_dir / "runs" / self.run_id / "run.log"),
        )
        
        self.ledger = LedgerManager(workspace_dir / "ledger.db")
        from .data.immutable_storage import ImmutableKlineStorage

        self.dataset_mgr = DatasetManager(
            workspace_dir / "data" / "datasets",
            storage=ImmutableKlineStorage(workspace_dir / "data" / "klines"),
            ledger=self.ledger,
            current_run_id=self.run_id,
        )
    
    def run(self) -> dict[str, Any]:
        log_event(LogComponent.ENGINE, f"Starting backtest run: {self.run_id}")

        trial_id = str(uuid.uuid4())
        self._trial_id = trial_id
        family_id = self._determine_family_id()
        self._family_id = family_id
        self._changed_key = None

        self.ledger.start_trial(
            trial_id=trial_id,
            run_id=self.run_id,
            parent_run_id=self.config.get("parent_run_id"),
            family_id=family_id,
            changed_key=None,
            status="started",
            started_at=datetime.now(timezone.utc),
        )

        results: dict[str, Any] | None = None
        status = "failed"
        error_message: str | None = None
        try:
            self._validate_registration()

            data, frozen = self._load_dataset()

            git_commit = self._get_git_commit()

            run_record = RunRecord(
                run_id=self.run_id,
                parent_run_id=self.config.get("parent_run_id"),
                git_commit=git_commit,
                dataset_hash=frozen.manifest_hash,
                environment="BACKTEST",
                config_snapshot=self.config,
                cost_scenario=self.config.get("cost_scenario", "base"),
                created_at=datetime.now(timezone.utc),
                manifest_hash=self.config.get("manifest_hash")
                or getattr(frozen, "batch_manifest_hash", None),
            )

            self.ledger.register_run(run_record)

            results = self._run_backtest(data, frozen)
            current_pnl = results["final_equity"] - results["initial_equity"]
            self.ledger.update_run_metrics(
                self.run_id,
                {
                    "total_pnl": current_pnl,
                    "final_equity": results["final_equity"],
                    "initial_equity": results["initial_equity"],
                },
            )

            report_path = self._generate_report(results)

            log_event(
                LogComponent.ENGINE,
                f"Backtest complete: {self.run_id}",
                total_trades=len(results["fills"]),
                report=str(report_path),
            )
            status = "completed"
            return results
        except Exception as exc:
            error_message = str(exc)
            status = "failed"
            raise
        finally:
            pnl = None
            if results is not None:
                pnl = results["final_equity"] - results["initial_equity"]
            self.ledger.finalize_trial(
                trial_id=trial_id,
                status=status,
                completed_at=datetime.now(timezone.utc),
                final_pnl=pnl,
                error_message=error_message,
                changed_key=self._changed_key,
            )

    def _determine_family_id(self) -> str:
        parent_id = self.config.get("parent_run_id")
        if not parent_id:
            return self.run_id
        current = parent_id
        seen: set[str] = set()
        while current and current not in seen:
            seen.add(current)
            parent_run = self.ledger.get_run(current)
            if not parent_run or not parent_run.parent_run_id:
                return current
            current = parent_run.parent_run_id
        return parent_id
    
    def _validate_registration(self) -> None:
        reg_config = self.config.get("registration", {})
        
        if not reg_config.get("hypothesis"):
            raise ValueError("Registration incomplete: 'hypothesis' is required")
        
        if not reg_config.get("keep_rule"):
            raise ValueError("Registration incomplete: 'keep_rule' is required")
        
        if not reg_config.get("drop_rule"):
            raise ValueError("Registration incomplete: 'drop_rule' is required")
        
        # ✅ P0: expected_effect 和 change 必填（无默认值）
        if not reg_config.get("expected_effect"):
            raise ValueError("Registration incomplete: 'expected_effect' is required")
        
        if not reg_config.get("change"):
            raise ValueError("Registration incomplete: 'change' is required")
        
        # 验证单条件变更（vs parent_run_id）
        parent_run_id = self.config.get("parent_run_id")
        if parent_run_id:
            self._validate_single_change(parent_run_id)
        
        if self._changed_key is None:
            self._changed_key = "baseline" if not self.config.get("parent_run_id") else reg_config["change"]

        exp = ExperimentRegistration(
            run_id=self.run_id,
            parent_run_id=self.config.get("parent_run_id"),
            change=reg_config["change"],  # ✅ 必填
            hypothesis=reg_config["hypothesis"],
            expected_effect=reg_config["expected_effect"],  # ✅ 必填
            keep_rule=reg_config["keep_rule"],
            drop_rule=reg_config["drop_rule"],
            evidence_ref=reg_config.get("evidence_ref"),
            trial_count=self.ledger.count_trials(self._family_id),
            uses_holdout=self.config.get("stage_acceptance", False),
            conclusion=None,
            decision=None,
        )
        
        self.ledger.register_experiment(exp)
    
    def _validate_single_change(self, parent_run_id: str) -> None:
        """验证相对于 parent_run_id 只改变了一个条件
        
        Args:
            parent_run_id: 父运行 ID
        
        Raises:
            ValueError: 如果改变了多个条件或无法验证
        """
        # 查找父运行的配置
        parent_run = self.ledger.get_run(parent_run_id)
        
        # ✅ P0: 父运行不存在时必须报错（不可绕过）
        if not parent_run:
            raise ValueError(
                f"Parent run {parent_run_id} not found. "
                f"Cannot validate single-change requirement. "
                f"If this is a baseline run, remove parent_run_id from config."
            )
        
        parent_config = parent_run.config_snapshot
        current_config = self.config
        
        # 比较条件配置
        parent_conditions = parent_config.get("conditions", {})
        current_conditions = current_config.get("conditions", {})
        
        changes = []
        
        # 检查所有条件键
        all_cond_ids = set(parent_conditions.keys()) | set(current_conditions.keys())
        
        for cond_id in all_cond_ids:
            parent_cond = parent_conditions.get(cond_id, {})
            current_cond = current_conditions.get(cond_id, {})
            
            if parent_cond != current_cond:
                changes.append(cond_id)
        
        # ✅ P0: 扩展检查范围，包含所有可能影响结果的参数
        params_to_check = [
            "risk_per_trade",
            "max_positions",
            "max_total_risk",
            "initial_equity",
            "dataset_id",
            "split",
            "cost_scenario",
            "fees",
            "symbols",
            "leverage_target",
        ]
        
        for param in params_to_check:
            if parent_config.get(param) != current_config.get(param):
                changes.append(param)
        
        # 验证只改变了一个条件
        if len(changes) == 0:
            # ✅ P0: 零改动必须报错（不可放行）
            raise ValueError(
                f"No changes detected vs parent {parent_run_id}. "
                f"Registration gate requires exactly ONE changed parameter. "
                f"If this is an exact replication, use a different parent_run_id."
            )
        elif len(changes) == 1:
            self._changed_key = changes[0]
            log_event(
                LogComponent.ENGINE,
                f"Single change validated vs parent {parent_run_id}",
                changed_param=changes[0],
            )
        else:
            raise ValueError(
                f"Multiple changes detected vs parent {parent_run_id}: {changes}. "
                f"Registration gate requires exactly ONE changed condition per run. "
                f"Changed parameters: {', '.join(changes)}"
            )
    
    def _load_dataset(self) -> tuple[dict[str, pd.DataFrame], Any]:
        dataset_id = self.config["dataset_id"]
        symbols = self.config.get("symbols", ["BTC-USDT", "ETH-USDT"])
        
        manifest_hash = self.config.get("manifest_hash")
        if manifest_hash:
            data, frozen = self.dataset_mgr.load_dataset_with_verification(
                dataset_id, manifest_hash, symbols
            )
        else:
            data, frozen = self.dataset_mgr.load_dataset(dataset_id, symbols)

        split = self.config.get("split", "train")
        
        if split == "test":
            if not self.config.get("stage_acceptance", False):
                raise ValueError("Cannot use test split without stage_acceptance=true")
        
        start_idx, end_idx = frozen.get_split(split)
        
        processed_data = {}
        for symbol, df in data.items():
            df = df.iloc[start_idx:end_idx].copy()
            
            # 记录原始统计（用于报告分离）
            total_bars = len(df)
            non_comparable_count = df.get("non_comparable", pd.Series([False] * len(df))).sum()
            external_count = df.get("external", pd.Series([False] * len(df))).sum()
            
            # 标记 non_comparable K 线（如果列不存在，创建全 False）
            if "non_comparable" not in df.columns:
                df["non_comparable"] = False
            
            if "external" not in df.columns:
                df["external"] = False
            
            # 保存完整数据（包含 non_comparable）用于统计分离
            df["_original_source"] = df.get("source", "htx")
            
            df = compute_all_features(df)
            df = compute_regime_labels(df)
            processed_data[symbol] = df
            
            log_event(
                LogComponent.DATA,
                f"Dataset loaded: {symbol}",
                split=split,
                total_bars=total_bars,
                non_comparable=int(non_comparable_count),
                external=int(external_count),
                usable_bars=len(df[~df["non_comparable"]]),
            )
        
        log_event(
            LogComponent.DATA,
            f"Dataset loaded: {dataset_id}",
            split=split,
            symbols=list(processed_data.keys()),
        )
        
        return processed_data, frozen
    
    def _get_git_commit(self) -> str:
        try:
            result = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                capture_output=True,
                text=True,
                check=True,
                cwd=self.workspace_dir.parent,
            )
            return result.stdout.strip()
        except Exception:
            return "unknown"
    
    def _run_backtest(
        self, data: dict[str, pd.DataFrame], frozen: Any
    ) -> dict[str, Any]:
        # ✅ 移除静默默认值 - 关键参数必须显式配置
        if "initial_equity" not in self.config:
            raise ValueError(
                "Config missing required field 'initial_equity'. "
                "Set initial_equity in your run config (e.g., 400.0 for 400 USDT)."
            )
        
        if "risk_per_trade" not in self.config:
            raise ValueError(
                "Config missing required field 'risk_per_trade'. "
                "Set risk_per_trade (e.g., 0.005 for 0.5% per trade)."
            )
        
        if "max_total_risk" not in self.config:
            raise ValueError(
                "Config missing required field 'max_total_risk'. "
                "Set max_total_risk (e.g., 0.015 for 1.5% total)."
            )
        
        if "max_positions" not in self.config:
            raise ValueError(
                "Config missing required field 'max_positions'. "
                "Set max_positions (e.g., 3)."
            )
        
        initial_equity = self.config["initial_equity"]
        cost_scenario = CostScenario(self.config.get("cost_scenario", "base"))
        
        fee_config = self.config.get("fees", {})
        fee_provider = create_fee_provider(
            use_authenticated=fee_config.get("use_authenticated", False),
            client=None,
            maker_rate=fee_config.get("maker_rate"),
            taker_rate=fee_config.get("taker_rate"),
        )
        
        broker = SimBroker(initial_equity, cost_scenario, fee_provider=fee_provider)
        sizer = PositionSizer(
            risk_per_trade_pct=self.config["risk_per_trade"],
            max_total_risk_pct=self.config["max_total_risk"],
            max_positions=self.config["max_positions"],
        )
        liq_checker = LiquidationChecker()
        
        state_machines = {symbol: StateMachine() for symbol in data.keys()}
        
        # ✅ 移除所有条件默认值（审查报告 080d 第5项）
        c01_config = self.config.get("conditions", {}).get("C01", {})
        if not c01_config.get("enabled", True):
            raise ValueError("C01 must be enabled (required entry condition)")
        
        if not c01_config.get("periods"):
            raise ValueError("C01 config missing required field 'periods'")
        if "threshold" not in c01_config:
            raise ValueError("C01 config missing required field 'threshold'")
        
        c01 = C01DonchianEnsemble(
            periods=c01_config["periods"],
            threshold=c01_config["threshold"],
        )
        
        c02_config = self.config.get("conditions", {}).get("C02", {})
        
        if "atr_period" not in c02_config:
            raise ValueError("C02 config missing required field 'atr_period'")
        if "atr_multiplier" not in c02_config:
            raise ValueError("C02 config missing required field 'atr_multiplier'")
        
        c02 = C02ATRStop(
            atr_period=c02_config["atr_period"],
            atr_multiplier=c02_config["atr_multiplier"],
        )
        
        all_decisions = []
        
        for symbol, df in data.items():
            log_event(
                LogComponent.ENGINE,
                f"Processing symbol: {symbol}",
                bars=len(df),
            )
            
            for idx in range(200, len(df)):
                bar = df.iloc[idx]
                
                # ✅ 排除 non_comparable K 线（不生成信号）
                if bar.get("non_comparable", False):
                    # 仅处理已有订单，不生成新信号
                    broker.process_next_bar(symbol, bar.to_dict())
                    continue
                
                broker.process_next_bar(symbol, bar.to_dict())
                
                ctx = DecisionContext(
                    symbol=symbol,
                    bar_time=bar["bar_time"],
                    df=df,
                    current_idx=idx,
                    current_position=None,
                )
                
                sm = state_machines[symbol]
                current_state = sm.get_state(symbol)
                current_position = sm.get_position(symbol)
                
                decision_record = {
                    "run_id": self.run_id,
                    "symbol": symbol,
                    "bar_time": bar["bar_time"],
                    "close": bar["close"],
                    "regime": bar.get("regime", "UNKNOWN"),
                    "state": current_state.value,
                    "action": "HOLD",
                }
                
                if current_state == StrategyState.FLAT:
                    ctx.current_position = None
                    c01_result = c01.evaluate(ctx, {})
                    
                    decision_record["c01_status"] = c01_result.status
                    decision_record["c01_value"] = c01_result.value
                    
                    if c01_result.status == "PASS" and c01_result.value is not None:
                        side = "LONG" if c01_result.value > 0 else "SHORT"
                        
                        atr = bar["atr14"]
                        stop_loss = c02.calculate_stop_loss(
                            bar["close"], atr, side
                        )
                        
                        pos_size = sizer.calculate_position(
                            symbol=symbol,
                            side=side,
                            entry_price=bar["close"],
                            stop_loss=stop_loss,
                            account_equity=broker.get_account_equity(),
                            contract_size=1.0,
                            min_order_size=1,
                            existing_positions=broker.get_positions(),
                        )
                        
                        if pos_size.skip_reason:
                            decision_record["action"] = "SKIP"
                            decision_record["skip_reason"] = pos_size.skip_reason
                        else:
                            ok, liq_dist, msg = liq_checker.check_liquidation_distance(
                                bar["close"], stop_loss, side, pos_size.leverage
                            )
                            
                            if ok and pos_size.size > 0:
                                sm.signal_entry(
                                    symbol=symbol,
                                    side=side,
                                    size=pos_size.size,
                                    entry_price=bar["close"],
                                    entry_time=bar["bar_time"],
                                    stop_loss=stop_loss,
                                    leverage=pos_size.leverage,
                                )
                                
                                broker.submit_order(
                                    Order(
                                        symbol=symbol,
                                        side=side,
                                        size=pos_size.size,
                                        order_type="LIMIT",
                                    )
                                )
                                
                                decision_record["action"] = "ENTER"
                                decision_record["side"] = side
                                decision_record["size"] = pos_size.size
                                decision_record["stop_loss"] = stop_loss
                
                elif current_state == StrategyState.ENTRY_PENDING:
                    sm.confirm_entry(symbol)
                    decision_record["action"] = "CONFIRM_ENTRY"
                
                elif current_state == StrategyState.OPEN:
                    if current_position:
                        ctx.current_position = {
                            "entry_price": current_position.entry_price,
                            "side": current_position.side,
                        }
                        
                        c02_result = c02.evaluate(ctx, {})
                        
                        decision_record["c02_status"] = c02_result.status
                        decision_record["c02_value"] = c02_result.value
                        
                        if c02_result.status == "PASS":
                            sm.signal_exit(symbol, "stop_loss")
                            
                            broker.submit_order(
                                Order(
                                    symbol=symbol,
                                    side="SHORT" if current_position.side == "LONG" else "LONG",
                                    size=current_position.size,
                                    order_type="MARKET",
                                    reduce_only=True,
                                )
                            )
                            
                            decision_record["action"] = "EXIT"
                            decision_record["exit_reason"] = "stop_loss"
                
                elif current_state == StrategyState.EXIT_PENDING:
                    closed_pos = sm.confirm_exit(symbol, bar["close"], bar["bar_time"])
                    if closed_pos:
                        decision_record["action"] = "CONFIRM_EXIT"
                
                all_decisions.append(decision_record)
        
        decisions_df = pd.DataFrame(all_decisions)
        
        fee_rates_record = {}
        for symbol, rates in broker.fee_rates_used.items():
            fee_rates_record[symbol] = rates.to_dict()
        
        return {
            "fills": broker.fills,
            "decisions": decisions_df,
            "final_equity": broker.get_account_equity(),
            "initial_equity": initial_equity,
            "fee_rates_used": fee_rates_record,
        }
    
    def _generate_report(self, results: dict[str, Any]) -> Path:
        output_dir = self.workspace_dir / "runs" / self.run_id
        output_dir.mkdir(parents=True, exist_ok=True)
        
        fills_data = [
            {
                "symbol": f.symbol,
                "side": f.side,
                "size": f.size,
                "price": f.price,
                "time": f.time,
                "fees": f.fees,
                "slippage": f.slippage,
                "is_entry": f.is_entry,
            }
            for f in results["fills"]
        ]
        
        fills_df = pd.DataFrame(fills_data)
        if not fills_df.empty:
            fills_df.to_parquet(output_dir / "fills.parquet", index=False)
        
        decisions_df = results["decisions"]
        if not decisions_df.empty:
            decisions_df.to_parquet(output_dir / "decisions.parquet", index=False)
        
        generator = ReportGenerator(self.run_id, output_dir)
        
        parent_comparison = None
        if self.config.get("parent_run_id"):
            parent_run = self.ledger.get_run(self.config["parent_run_id"])
            if parent_run:
                parent_comparison = {
                    "parent_run_id": parent_run.run_id,
                    "change": self.config.get("registration", {}).get("change", "N/A"),
                    "current_pnl": results["final_equity"] - results["initial_equity"],
                    "parent_pnl": 0,
                    "pnl_diff": 0,
                }
        
        return generator.generate_report(
            config=self.config,
            fills=fills_data,
            decisions=decisions_df,
            parent_comparison=parent_comparison,
        )
