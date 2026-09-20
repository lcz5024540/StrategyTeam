package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;

/** Runtime client and policy for one follower trend account. */
record HtxTrendMirrorAccount(String accountLabel,
                             HtxUsdtSwapService swap,
                             HtxSwapFeeRateResolver feeRateResolver,
                             boolean syncLeverage,
                             boolean blockEntryOnLeverageSyncFailure,
                             boolean blockPrimaryCloseUntilSynced) {
}
