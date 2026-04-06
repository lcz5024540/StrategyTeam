#!/usr/bin/env python3
import os
import ssl
import sys
import time
from typing import Any

import certifi
import httpx

import py_clob_client.http_helpers.helpers as clob_helpers
from py_clob_client.exceptions import PolyApiException


def env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw == "":
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def env_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if raw is None or raw == "":
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def clear_proxy_env() -> None:
    for proxy_var in (
        "HTTP_PROXY",
        "HTTPS_PROXY",
        "ALL_PROXY",
        "http_proxy",
        "https_proxy",
        "all_proxy",
    ):
        os.environ.pop(proxy_var, None)


def build_ssl_context() -> ssl.SSLContext:
    context = ssl.create_default_context(cafile=certifi.where())
    return context


def build_http_client() -> httpx.Client:
    clear_proxy_env()
    timeout = httpx.Timeout(
        connect=env_float("POLY_HTTP_CONNECT_TIMEOUT_SECONDS", 10.0),
        read=env_float("POLY_HTTP_READ_TIMEOUT_SECONDS", 30.0),
        write=env_float("POLY_HTTP_WRITE_TIMEOUT_SECONDS", 30.0),
        pool=env_float("POLY_HTTP_POOL_TIMEOUT_SECONDS", 10.0),
    )
    transport = httpx.HTTPTransport(
        retries=env_int("POLY_HTTP_CONNECT_RETRIES", 2),
    )
    return httpx.Client(
        http2=False,
        timeout=timeout,
        trust_env=False,
        verify=build_ssl_context(),
        transport=transport,
        headers={"User-Agent": "CoinStrategy/py-clob-client"},
    )


def _log(message: str) -> None:
    sys.stderr.write(f"[polymarket_http] {message}\n")
    sys.stderr.flush()


def _is_retryable_http_status(status_code: int) -> bool:
    return status_code in (408, 425, 429, 500, 502, 503, 504)


def install_py_clob_http_overrides() -> None:
    original_overload_headers = clob_helpers.overloadHeaders

    def request_with_retry(endpoint: str, method: str, headers=None, data=None):
        attempts = max(1, env_int("POLY_HTTP_REQUEST_ATTEMPTS", 3))
        base_sleep = max(0.05, env_float("POLY_HTTP_RETRY_BASE_SLEEP_SECONDS", 0.4))
        last_exc: Exception | None = None
        last_error_type = "unknown"
        for attempt in range(1, attempts + 1):
            started_at = time.time()
            client = build_http_client()
            try:
                merged_headers = original_overload_headers(method, headers)
                if isinstance(data, str):
                    resp = client.request(
                        method=method,
                        url=endpoint,
                        headers=merged_headers,
                        content=data.encode("utf-8"),
                    )
                else:
                    resp = client.request(
                        method=method,
                        url=endpoint,
                        headers=merged_headers,
                        json=data,
                    )
                if resp.status_code == 200:
                    _log(
                        f"request succeeded method={method} endpoint={endpoint} "
                        f"attempt={attempt} status={resp.status_code} elapsedMs={int((time.time() - started_at) * 1000)}"
                    )
                    try:
                        return resp.json()
                    except ValueError:
                        return resp.text
                if _is_retryable_http_status(resp.status_code) and attempt < attempts:
                    _log(
                        f"request retrying method={method} endpoint={endpoint} attempt={attempt} "
                        f"status={resp.status_code} elapsedMs={int((time.time() - started_at) * 1000)}"
                    )
                    time.sleep(base_sleep * attempt)
                    continue
                last_error_type = f"http_status_{resp.status_code}"
                raise PolyApiException(resp)
            except httpx.RequestError as exc:
                last_exc = exc
                last_error_type = exc.__class__.__name__
                _log(
                    f"request failed method={method} endpoint={endpoint} attempt={attempt} "
                    f"errorType={last_error_type} message={exc}"
                )
                if attempt >= attempts:
                    break
                time.sleep(base_sleep * attempt)
            finally:
                try:
                    client.close()
                except Exception:
                    pass
        raise PolyApiException(
            error_msg=f"Request exception! endpoint={endpoint} method={method} errorType={last_error_type} lastError={last_exc}"
        )

    clob_helpers._http_client = build_http_client()
    clob_helpers.request = request_with_retry
    clob_helpers.get = lambda endpoint, headers=None, data=None: request_with_retry(endpoint, clob_helpers.GET, headers, data)
    clob_helpers.post = lambda endpoint, headers=None, data=None: request_with_retry(endpoint, clob_helpers.POST, headers, data)
    clob_helpers.delete = lambda endpoint, headers=None, data=None: request_with_retry(endpoint, clob_helpers.DELETE, headers, data)
    clob_helpers.put = lambda endpoint, headers=None, data=None: request_with_retry(endpoint, clob_helpers.PUT, headers, data)
