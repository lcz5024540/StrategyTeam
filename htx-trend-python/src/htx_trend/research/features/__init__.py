"""技术指标特征计算"""

from .indicators import FeatureCalculator, compute_all_features
from .daily_resample import (
    DAILY_RESAMPLE_FEATURE,
    detect_daily_open_hour,
    resample_4h_to_daily,
    resample_4h_to_daily_utc00,
)
from .funding_asof import apply_bar_settlements, attach_funding_as_of, settlements_in_bar

# 向后兼容旧测试
from ..legacy_features import donchian, performance, regression_slope

__all__ = [
    "FeatureCalculator", 
    "compute_all_features",
    "DAILY_RESAMPLE_FEATURE",
    "detect_daily_open_hour",
    "resample_4h_to_daily",
    "resample_4h_to_daily_utc00",
    "attach_funding_as_of",
    "apply_bar_settlements",
    "settlements_in_bar",
    "donchian",
    "performance",
    "regression_slope",
]
