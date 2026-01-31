package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.rubik.*;

public class OkxRubikService extends OkxService {

    public OkxRubikService(OkxApi okxApi) {
        super(okxApi);
    }

    public OkxRestResponse<String> getSupportCoin() {
        return execute(okxApi.getSupportCoin(), "Get support coin failed");
    }

    public OkxRestResponse<OkxTakerVolume> getTakerVolume(String ccy, String instType, String begin, String end, String period) {
        return execute(okxApi.getTakerVolume(ccy, instType, begin, end, period), "Get taker volume failed");
    }

    public OkxRestResponse<OkxMarginLendingRatio> getMarginLendingRatio(String ccy, String begin, String end, String period) {
        return execute(okxApi.getMarginLendingRatio(ccy, begin, end, period), "Get margin lending ratio failed");
    }

    public OkxRestResponse<OkxLongShortRatio> getLongShortAccountRatio(String instId, String begin, String end, String period) {
        return execute(okxApi.getLongShortAccountRatio(instId, begin, end, period), "Get long/short account ratio failed");
    }

    public OkxRestResponse<OkxOpenInterestVolume> getOpenInterestVolume(String ccy, String instType, String begin, String end, String period) {
        return execute(okxApi.getOpenInterestVolume(ccy, instType, begin, end, period), "Get open interest volume failed");
    }

    public OkxRestResponse<OkxOpenInterestVolume> getOptionOpenInterestVolume(String ccy, String period) {
        return execute(okxApi.getOptionOpenInterestVolume(ccy, period), "Get option open interest volume failed");
    }

    public OkxRestResponse<OkxPutCallRatio> getOptionOpenInterestVolumeRatio(String ccy, String period) {
        return execute(okxApi.getOptionOpenInterestVolumeRatio(ccy, period), "Get option open interest volume ratio failed");
    }

    public OkxRestResponse<OkxOpenInterestVolumeExpiry> getOptionOpenInterestVolumeExpiry(String ccy, String period) {
        return execute(okxApi.getOptionOpenInterestVolumeExpiry(ccy, period), "Get option open interest volume expiry failed");
    }

    public OkxRestResponse<OkxOpenInterestVolumeStrike> getOptionOpenInterestVolumeStrike(String ccy, String expTime, String period) {
        return execute(okxApi.getOptionOpenInterestVolumeStrike(ccy, expTime, period), "Get option open interest volume strike failed");
    }

    public OkxRestResponse<OkxOpenInterestHistory> getOpenInterestHistory(String instId, String period, String begin, String end, String limit) {
        return execute(okxApi.getOpenInterestHistory(instId, period, begin, end, limit), "Get open interest history failed");
    }

    public OkxRestResponse<OkxTakerVolume> getTakerVolumeContract(String instId, String period, String begin, String end, String limit) {
        return execute(okxApi.getTakerVolumeContract(instId, period, begin, end, limit), "Get taker volume contract failed");
    }

    public OkxRestResponse<OkxLongShortRatio> getLongShortPositionRatioContractTopTrader(String instId, String period, String begin, String end, String limit) {
        return execute(okxApi.getLongShortPositionRatioContractTopTrader(instId, period, begin, end, limit), "Get long/short position ratio contract top trader failed");
    }

    public OkxRestResponse<OkxLongShortRatio> getLongShortAccountRatioContract(String instId, String period, String begin, String end, String limit) {
        return execute(okxApi.getLongShortAccountRatioContract(instId, period, begin, end, limit), "Get long/short account ratio contract failed");
    }

    public OkxRestResponse<OkxTakerVolume> getOptionTakerBlockVolume(String ccy, String period, String begin, String end) {
        return execute(okxApi.getOptionTakerBlockVolume(ccy, period, begin, end), "Get option taker block volume failed");
    }

    public OkxRestResponse<OkxTakerFlow> getTakerFlow(String ccy, String period) {
        return execute(okxApi.getTakerFlow(ccy, period), "Get taker flow failed");
    }
}
