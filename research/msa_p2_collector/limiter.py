"""限频：相邻公开请求的最小间隔。"""


class RateLimiter:
    def __init__(self, max_per_second, *, clock, sleep):
        if max_per_second <= 0:
            raise ValueError('限频必须为正')
        self.interval = 1 / max_per_second
        self.clock = clock
        self.sleep = sleep
        self._next_at = None

    def acquire(self):
        now = self.clock()
        if self._next_at is None:
            self._next_at = now
        wait = self._next_at - now
        if wait > 0:
            self.sleep(wait)
            now = self.clock()
        self._next_at = now + self.interval
