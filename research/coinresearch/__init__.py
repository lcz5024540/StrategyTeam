"""Offline, direction-free data and statistical research tools."""
from .config import Config
from .data import build_dataset
from .guard import BlindGuardError, SealedDataset
