"""结构化 JSON 日志系统"""

from __future__ import annotations

import json
import logging
import sys
from datetime import UTC, datetime
from enum import Enum
from typing import Any


class LogLevel(str, Enum):
    DEBUG = "DEBUG"
    INFO = "INFO"
    WARNING = "WARNING"
    ERROR = "ERROR"
    CRITICAL = "CRITICAL"


class LogComponent(str, Enum):
    DATA = "data"
    FEATURES = "features"
    CONDITIONS = "conditions"
    STRATEGY = "strategy"
    RISK = "risk"
    ENGINE = "engine"
    BACKTEST = "backtest"
    LEDGER = "ledger"
    REPORTS = "reports"
    CLI = "cli"
    OPS = "ops"


class StructuredJsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        log_data = {
            "timestamp": datetime.now(UTC).isoformat(),
            "level": record.levelname,
            "component": getattr(record, "component", "unknown"),
            "event": getattr(record, "event", record.getMessage()),
            "run_id": getattr(record, "run_id", None),
        }
        
        if hasattr(record, "extra_data"):
            log_data.update(record.extra_data)
        
        if record.exc_info:
            log_data["exception"] = self.formatException(record.exc_info)
        
        return json.dumps(log_data, ensure_ascii=False)


_logger: logging.Logger | None = None
_current_run_id: str | None = None


def setup_logging(
    level: LogLevel = LogLevel.INFO,
    json_output: bool = True,
    log_file: str | None = None,
) -> logging.Logger:
    global _logger
    
    logger = logging.getLogger("htx_trend.research")
    logger.setLevel(getattr(logging, level.value))
    logger.handlers.clear()
    
    console_handler = logging.StreamHandler(sys.stdout)
    
    if json_output:
        console_handler.setFormatter(StructuredJsonFormatter())
    else:
        console_handler.setFormatter(
            logging.Formatter("%(asctime)s [%(levelname)s] %(component)s: %(message)s")
        )
    
    logger.addHandler(console_handler)
    
    if log_file:
        file_handler = logging.FileHandler(log_file)
        file_handler.setFormatter(StructuredJsonFormatter())
        logger.addHandler(file_handler)
    
    _logger = logger
    return logger


def set_run_id(run_id: str) -> None:
    global _current_run_id
    _current_run_id = run_id


def log_event(
    component: LogComponent,
    event: str,
    level: LogLevel = LogLevel.INFO,
    **extra_data: Any,
) -> None:
    global _logger, _current_run_id
    
    if _logger is None:
        _logger = setup_logging()
    
    log_level = getattr(logging, level.value)
    
    extra = {
        "component": component.value,
        "event": event,
        "run_id": _current_run_id,
        "extra_data": extra_data,
    }
    
    _logger.log(log_level, event, extra=extra)


def get_logger() -> logging.Logger:
    global _logger
    if _logger is None:
        _logger = setup_logging()
    return _logger
