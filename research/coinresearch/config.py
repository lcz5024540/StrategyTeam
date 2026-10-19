from dataclasses import dataclass


@dataclass(frozen=True)
class Config:
    neff_threshold: float = 4400
    hard_floor: float = 1500
    nominal_floor: int = 1000
    h4: int = 14400
    p: int = 12
    win90: int = 540
    adv_days: float = 90
    turn_min: float = 5e6
    list_days: float = 180
    gap_max: float = 0.01
    warm: int = 1180
    last_open: int = 1792353600
    z: float = 2.486
    rt_cost: float = 12
    ndraw: int = 1000
    seed: int = 20261019
    frac: float = 0.2
    funding_gap_hours: float = 9
    rebalance_grace_hours: float = 8
    cap: float | None = None
    deff_block_days: float = 5
    deff_icc: float | None = None

    def __post_init__(self):
        if not 0 < self.hard_floor <= self.neff_threshold or self.nominal_floor < 1:
            raise ValueError('invalid sample thresholds')
        if min(self.h4, self.p, self.win90, self.adv_days, self.ndraw) <= 0:
            raise ValueError('grid, windows and draws must be positive')
        if not 0 < self.gap_max <= 1 or not 0 < self.frac <= .5:
            raise ValueError('invalid gap_max or frac')
        if self.deff_block_days <= 0 or (self.deff_icc is not None and not 0 <= self.deff_icc <= 1):
            raise ValueError('invalid DEFF configuration')

    @property
    def per(self):
        return self.h4 * self.p
