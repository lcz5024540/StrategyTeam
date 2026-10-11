"""核心回测运行器"""

from __future__ import annotations

import hashlib
import subprocess
from datetime import datetime
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
        self.dataset_mgr = DatasetManager(workspace_dir / "data" / "datasets")
    
    def run(self) -> dict[str, Any]:
        log_event(LogComponent.ENGINE, f"Starting backtest run: {self.run_id}")
        
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
            created_at=datetime.now(),
        )
        
        self.ledger.register_run(run_record)
        
        results = self._run_backtest(data, frozen)
        
        report_path = self._generate_report(results)
        
        log_event(
            LogComponent.ENGINE,
            f"Backtest complete: {self.run_id}",
            total_trades=len(results["fills"]),
            report=str(report_path),
        )
        
        return results
    
    def _validate_registration(self) -> None:
        reg_config = self.config.get("registration", {})
        
        if not reg_config.get("hypothesis"):
            raise ValueError("Registration incomplete: 'hypothesis' is required")
        
        if not reg_config.get("keep_rule"):
            raise ValueError("Registration incomplete: 'keep_rule' is required")
        
        if not reg_config.get("drop_rule"):
            raise ValueError("Registration incomplete: 'drop_rule' is required")
        
        exp = ExperimentRegistration(
            run_id=self.run_id,
            parent_run_id=self.config.get("parent_run_id"),
            change=reg_config.get("change", "Initial baseline"),
            hypothesis=reg_config["hypothesis"],
            expected_effect=reg_config.get("expected_effect", "Establish baseline"),
            keep_rule=reg_config["keep_rule"],
            drop_rule=reg_config["drop_rule"],
            evidence_ref=reg_config.get("evidence_ref"),
            trial_count=1,
            uses_holdout=self.config.get("stage_acceptance", False),
            conclusion=None,
            decision=None,
        )
        
        self.ledger.register_experiment(exp)
    
    def _load_dataset(self) -> tuple[dict[str, pd.DataFrame], Any]:
        dataset_id = self.config["dataset_id"]
        symbols = self.config.get("symbols", ["BTC-USDT", "ETH-USDT"])
        
        data, frozen = self.dataset_mgr.load_dataset(dataset_id, symbols)
        
        split = self.config.get("split", "train")
        
        if split == "test":
            if not self.config.get("stage_acceptance", False):
                raise ValueError("Cannot use test split without stage_acceptance=true")
        
        start_idx, end_idx = frozen.get_split(split)
        
        processed_data = {}
        for symbol, df in data.items():
            df = df.iloc[start_idx:end_idx].copy()
            df = compute_all_features(df)
            df = compute_regime_labels(df)
            processed_data[symbol] = df
        
        log_event(
            LogComponent.DATA,
            f"Dataset loaded: {dataset_id}",
            split=split,
            symbols=list(processed_data.keys()),
            bars={s: len(df) for s, df in processed_data.items()},
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
        initial_equity = self.config.get("initial_equity", 1000.0)
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
            risk_per_trade_pct=self.config.get("risk_per_trade", 0.005),
            max_total_risk_pct=self.config.get("max_total_risk", 0.015),
            max_positions=self.config.get("max_positions", 3),
        )
        liq_checker = LiquidationChecker()
        
        state_machines = {symbol: StateMachine() for symbol in data.keys()}
        
        # 从配置读取条件参数（必须显式传递）
        c01_config = self.config.get("conditions", {}).get("C01", {})
        if not c01_config.get("enabled", True):
            raise ValueError("C01 must be enabled (required entry condition)")
        
        c01 = C01DonchianEnsemble(
            periods=c01_config.get("periods", [42, 84, 126, 168]),
            threshold=c01_config.get("threshold", 0.25),
        )
        
        c02_config = self.config.get("conditions", {}).get("C02", {})
        c02 = C02ATRStop(
            atr_period=c02_config.get("atr_period", 14),
            atr_multiplier=c02_config.get("atr_multiplier", 2.0),
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
