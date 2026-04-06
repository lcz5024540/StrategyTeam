#!/usr/bin/env bash

# [SCRIPT][FIXED-ONCE] Execution wallet address on Polygon / Polymarket.
export POLYMARKET_EXECUTION_WALLET_ADDRESS=""
# [SCRIPT][FIXED-ONCE] Private key for the execution wallet. Keep only in local env, never commit.
export POLYMARKET_EXECUTION_PRIVATE_KEY=""
# [SCRIPT][FIXED-ONCE] Funder address used by Polymarket CLOB auth. Often same as execution wallet.
export POLYMARKET_EXECUTION_FUNDER_ADDRESS=""

# [SCRIPT][FIXED-ONCE] CLOB API key derived from wallet auth. Can stay empty before first derive.
export POLYMARKET_CLOB_API_KEY=""
# [SCRIPT][FIXED-ONCE] CLOB API secret derived from wallet auth.
export POLYMARKET_CLOB_API_SECRET=""
# [SCRIPT][FIXED-ONCE] CLOB API passphrase derived from wallet auth.
export POLYMARKET_CLOB_API_PASSPHRASE=""

# [SCRIPT][FIXED-ONCE] Python executable used by the official py-clob-client bridge.
export POLYMARKET_PYTHON_EXECUTABLE="/opt/homebrew/bin/python3.11"

# [TUNE] Python HTTP client stability knobs for Polymarket SDK bridge.
export POLY_HTTP_CONNECT_RETRIES="2"
export POLY_HTTP_REQUEST_ATTEMPTS="3"
export POLY_HTTP_CONNECT_TIMEOUT_SECONDS="10"
export POLY_HTTP_READ_TIMEOUT_SECONDS="30"
export POLY_HTTP_WRITE_TIMEOUT_SECONDS="30"
export POLY_HTTP_POOL_TIMEOUT_SECONDS="10"
export POLY_HTTP_RETRY_BASE_SLEEP_SECONDS="0.4"

# [TUNE] Optional Python bridge execution timeout override when a single call hangs too long.
export POLYMARKET_PYTHON_TIMEOUT_SECONDS="45"
