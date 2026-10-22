"""可恢复传输失败的有限次重连。"""


class RetryableTransportError(Exception):
    """连接重置、超时或交易所 429/5xx。业务校验错误不使用本类。"""


def call_with_retry(operation, *, max_attempts, sleep, on_retry):
    if max_attempts < 1:
        raise ValueError('重试次数至少为 1')
    for attempt in range(1, max_attempts + 1):
        try:
            return operation()
        except RetryableTransportError as exc:
            if attempt == max_attempts:
                raise
            on_retry(attempt, exc)
            sleep(min(30, 0.5 * attempt))
    raise RuntimeError('重试循环未返回')
