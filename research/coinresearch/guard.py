"""Seal verification; no signal construction or ranking is provided."""
from pathlib import Path
import hashlib
import json
import numpy as np


class BlindGuardError(ValueError):
    pass


def verify_prereg(prereg_path, prereg_sha256):
    if not prereg_path or not prereg_sha256:
        raise BlindGuardError('prereg path and SHA256 are required')
    try:
        payload = Path(prereg_path).read_bytes()
    except OSError as exc:
        raise BlindGuardError('prereg file unavailable') from exc
    actual = hashlib.sha256(payload).hexdigest()
    if actual != prereg_sha256:
        raise BlindGuardError('prereg SHA256 mismatch')
    return payload


class SealedDataset:
    """Externally prepared period-by-coin observations, released only after sealing.

    Prereg JSON must contain a nonempty split specification. This checks artifact
    integrity, not the truth of the preregistration's scientific claims.
    """
    def __init__(self, values, period_index):
        self.__values = np.array(values, dtype=float, copy=True)
        self.__periods = np.array(period_index, dtype=np.int64, copy=True)
        if not np.array_equal(self.__periods, np.asarray(period_index)):
            raise ValueError('calendar period indices must be integers')
        if self.__values.ndim != 2 or len(self.__values) != len(self.__periods):
            raise ValueError('expected period-by-coin matrix and period index')
        if len(self.__periods) < 2 or not np.all(np.diff(self.__periods) == 1):
            raise ValueError('period index must be a complete consecutive calendar grid')
        if not np.isfinite(self.__values).all() or not self.__values.shape[1]:
            raise ValueError('observations must be finite and nonempty')
        self.__sha = None
        self.__path = None

    @property
    def nominal_n(self):
        """Number of scalar observations, readable without releasing values."""
        return int(self.__values.size)

    @property
    def sealed(self):
        return self.__sha is not None

    @property
    def prereg_sha256(self):
        return self.__sha

    def seal(self, *, prereg_path, prereg_sha256):
        payload = verify_prereg(prereg_path, prereg_sha256)
        try:
            prereg = json.loads(payload)
        except (ValueError, TypeError) as exc:
            raise BlindGuardError('prereg must be JSON with split specification') from exc
        if not isinstance(prereg, dict) or not prereg.get('split'):
            raise BlindGuardError('completed split specification required')
        if self.sealed and self.__sha != prereg_sha256:
            raise BlindGuardError('dataset already sealed to another prereg')
        self.__path, self.__sha = str(Path(prereg_path).resolve()), prereg_sha256
        return self

    def require_sealed(self, *, prereg_path, prereg_sha256):
        verify_prereg(prereg_path, prereg_sha256)
        if not self.sealed or self.__sha != prereg_sha256:
            raise BlindGuardError('dataset has not been sealed to this prereg')
        if str(Path(prereg_path).resolve()) != self.__path:
            raise BlindGuardError('prereg path differs from seal')

    def directional_observations(self, *, prereg_path, prereg_sha256):
        self.require_sealed(prereg_path=prereg_path, prereg_sha256=prereg_sha256)
        return self.__values.copy()
