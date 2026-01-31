package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.rubik.*;

public class OkxRubikService extends OkxService {

    public OkxRubikService(OkxApi okxApi) {
        super(okxApi);
    }

    /**
     * Get support coin
     * <br>
     * Retrieve the support coin of the trading data.
     *
     * @return Support coin list
     */
    public OkxRestResponse<String> getSupportCoin() {
        return execute(okxApi.getSupportCoin(), "Get support coin failed");
    }

    /**
     * Get taker volume
     * <br>
     * Retrieve the taker volume for spot and contract.
     *
     * @param ccy      Currency, e.g. BTC
     * @param instType Instrument type (SPOT, SWAP, FUTURES, OPTION)
     * @param begin    Begin timestamp
     * @param end      End timestamp
     * @param period   Period (5m, 1H, 1D)
     * @return Taker volume
     */
    public OkxRestResponse<OkxTakerVolume> getTakerVolume(String ccy, String instType, String begin, String end, String period) {
        return execute(okxApi.getTakerVolume(ccy, instType, begin, end, period), "Get taker volume failed");
    }

    /**
     * Get margin lending ratio
     * <br>
     * Retrieve the margin lending ratio.
     *
     * @param ccy    Currency
     * @param begin  Begin timestamp
     * @param end    End timestamp
     * @param period Period
     * @return Margin lending ratio
     */
    public OkxRestResponse<OkxMarginLendingRatio> getMarginLendingRatio(String ccy, String begin, String end, String period) {
        return execute(okxApi.getMarginLendingRatio(ccy, begin, end, period), "Get margin lending ratio failed");
    }

    /**
     * Get long/short account ratio (Top Traders)
     * <br>
     * Retrieve the long/short account ratio of top traders.
     *
     * @param instId Instrument ID
     * @param begin  Begin timestamp
     * @param end    End timestamp
     * @param period Period
     * @return Long/short account ratio
     */
    public OkxRestResponse<OkxLongShortRatio> getLongShortAccountRatio(String instId, String begin, String end, String period) {
        return execute(okxApi.getLongShortAccountRatio(instId, begin, end, period), "Get long/short account ratio failed");
    }

    /**
     * Get contract open interest and volume
     * <br>
     * Retrieve the open interest and volume for contracts.
     *
     * @param ccy      Currency
     * @param instType Instrument type
     * @param begin    Begin timestamp
     * @param end      End timestamp
     * @param period   Period
     * @return Open interest and volume
     */
    public OkxRestResponse<OkxOpenInterestVolume> getOpenInterestVolume(String ccy, String instType, String begin, String end, String period) {
        return execute(okxApi.getOpenInterestVolume(ccy, instType, begin, end, period), "Get open interest volume failed");
    }

    /**
     * Get option open interest and volume
     * <br>
     * Retrieve the open interest and volume for options.
     *
     * @param ccy    Currency
     * @param period Period
     * @return Option open interest and volume
     */
    public OkxRestResponse<OkxOpenInterestVolume> getOptionOpenInterestVolume(String ccy, String period) {
        return execute(okxApi.getOptionOpenInterestVolume(ccy, period), "Get option open interest volume failed");
    }

    /**
     * Get put/call ratio
     * <br>
     * Retrieve the put/call ratio for options.
     *
     * @param ccy    Currency
     * @param period Period
     * @return Put/call ratio
     */
    public OkxRestResponse<OkxPutCallRatio> getOptionOpenInterestVolumeRatio(String ccy, String period) {
        return execute(okxApi.getOptionOpenInterestVolumeRatio(ccy, period), "Get option open interest volume ratio failed");
    }

    /**
     * Get option open interest and volume by expiry
     * <br>
     * Retrieve the open interest and volume for options by expiry.
     *
     * @param ccy    Currency
     * @param period Period
     * @return Option open interest and volume by expiry
     */
    public OkxRestResponse<OkxOpenInterestVolumeExpiry> getOptionOpenInterestVolumeExpiry(String ccy, String period) {
        return execute(okxApi.getOptionOpenInterestVolumeExpiry(ccy, period), "Get option open interest volume expiry failed");
    }

    /**
     * Get option open interest and volume by strike
     * <br>
     * Retrieve the open interest and volume for options by strike price.
     *
     * @param ccy     Currency
     * @param expTime Expiry time
     * @param period  Period
     * @return Option open interest and volume by strike
     */
    public OkxRestResponse<OkxOpenInterestVolumeStrike> getOptionOpenInterestVolumeStrike(String ccy, String expTime, String period) {
        return execute(okxApi.getOptionOpenInterestVolumeStrike(ccy, expTime, period), "Get option open interest volume strike failed");
    }

    /**
     * Get open interest history
     * <br>
     * Retrieve the open interest history for contracts.
     *
     * @param instId Instrument ID
     * @param period Period
     * @param begin  Begin timestamp
     * @param end    End timestamp
     * @param limit  Limit
     * @return Open interest history
     */
    public OkxRestResponse<OkxOpenInterestHistory> getOpenInterestHistory(String instId, String period, String begin, String end, String limit) {
        return execute(okxApi.getOpenInterestHistory(instId, period, begin, end, limit), "Get open interest history failed");
    }

    /**
     * Get taker volume contract
     * <br>
     * Retrieve the taker volume for contracts.
     *
     * @param instId Instrument ID
     * @param period Period
     * @param begin  Begin timestamp
     * @param end    End timestamp
     * @param limit  Limit
     * @return Taker volume contract
     */
    public OkxRestResponse<OkxTakerVolume> getTakerVolumeContract(String instId, String period, String begin, String end, String limit) {
        return execute(okxApi.getTakerVolumeContract(instId, period, begin, end, limit), "Get taker volume contract failed");
    }

    /**
     * Get long/short position ratio (Top Traders)
     * <br>
     * Retrieve the long/short position ratio of top traders.
     *
     * @param instId Instrument ID
     * @param period Period
     * @param begin  Begin timestamp
     * @param end    End timestamp
     * @param limit  Limit
     * @return Long/short position ratio
     */
    public OkxRestResponse<OkxLongShortRatio> getLongShortPositionRatioContractTopTrader(String instId, String period, String begin, String end, String limit) {
        return execute(okxApi.getLongShortPositionRatioContractTopTrader(instId, period, begin, end, limit), "Get long/short position ratio contract top trader failed");
    }

    /**
     * Get long/short account ratio (All Users)
     * <br>
     * Retrieve the long/short account ratio of all users.
     *
     * @param instId Instrument ID
     * @param period Period
     * @param begin  Begin timestamp
     * @param end    End timestamp
     * @param limit  Limit
     * @return Long/short account ratio
     */
    public OkxRestResponse<OkxLongShortRatio> getLongShortAccountRatioContract(String instId, String period, String begin, String end, String limit) {
        return execute(okxApi.getLongShortAccountRatioContract(instId, period, begin, end, limit), "Get long/short account ratio contract failed");
    }

    /**
     * Get option taker block volume
     * <br>
     * Retrieve the taker block volume for options.
     *
     * @param ccy    Currency
     * @param period Period
     * @param begin  Begin timestamp
     * @param end    End timestamp
     * @return Option taker block volume
     */
    public OkxRestResponse<OkxTakerVolume> getOptionTakerBlockVolume(String ccy, String period, String begin, String end) {
        return execute(okxApi.getOptionTakerBlockVolume(ccy, period, begin, end), "Get option taker block volume failed");
    }

    /**
     * Get taker flow
     * <br>
     * Retrieve the taker flow.
     *
     * @param ccy    Currency
     * @param period Period
     * @return Taker flow
     */
    public OkxRestResponse<OkxTakerFlow> getTakerFlow(String ccy, String period) {
        return execute(okxApi.getTakerFlow(ccy, period), "Get taker flow failed");
    }
}
