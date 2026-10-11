"""技术指标特征计算"""

from .indicators import FeatureCalculator, compute_all_features
from .daily_resample import (
    DAILY_RESAMPLE_FEATURE,
    detect_daily_open_hour,
    resample_4h_to_daily,
    resample_4h_to_daily_utc00,
)
from .funding_asof import attach_funding_as_of

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
    "donchian",
    "performance",
    "regression_slope",
]
