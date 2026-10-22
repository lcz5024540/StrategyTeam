"""HTX 公开 REST。不签名、不读取私有密钥。"""
from __future__ import annotations

import json
from urllib.parse import urlencode
import urllib.error
import urllib.request

from .retry import RetryableTransportError


class HttpTransport:
    """公开 GET。429/5xx 与连接失败抛出可重试错误，不签名。"""

    def __init__(self, base_url='https://api.hbdm.com', timeout=10):
        self.base_url = base_url.rstrip('/')
        self.timeout = timeout

    def get(self, path, params):
        query = urlencode(params)
        request = urllib.request.Request(f'{self.base_url}{path}?{query}', method='GET')
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                status = getattr(response, 'status', 200)
                body = response.read()
        except urllib.error.HTTPError as exc:
            if exc.code == 429 or exc.code >= 500:
                raise RetryableTransportError(f'http {exc.code}') from exc
            raise
        except urllib.error.URLError as exc:
            raise RetryableTransportError(exc.reason.__class__.__name__) from exc
        if status == 429 or status >= 500:
            raise RetryableTransportError(f'http {status}')
        payload = json.loads(body.decode())
        if payload.get('status') not in (None, 'ok'):
            raise RetryableTransportError(str(payload.get('err_code') or 'status'))
        return payload


class PublicHtxClient(HttpTransport):
    """与 HttpTransport 相同的公开客户端，保留原构造方式。"""
