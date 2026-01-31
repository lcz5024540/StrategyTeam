package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.account.*;

import java.util.HashMap;
import java.util.Map;

public class OkxAccountService extends OkxService {

    public OkxAccountService(OkxApi okxApi) {
        super(okxApi);
    }

    /**
     * 获取余额 (Get Balance)
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-balance">API Doc</a>
     */
    public OkxRestResponse<Balance> getBalance(String ccy) {
        return execute(okxApi.getBalance(ccy), "Get balance failed");
    }

    /**
     * 设置杠杆倍数 (Set Leverage)
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-set-leverage">API Doc</a>
     */
    public OkxRestResponse<Object> setLeverage(String instId, String mgnMode, String lever) {
        Map<String, Object> req = new HashMap<>();
        req.put("instId", instId);
        req.put("mgnMode", mgnMode);
        req.put("lever", lever);
        return execute(okxApi.setLeverage(req), "Set leverage failed");
    }

    /**
     * 获取持仓信息 (Get Positions)
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-positions">API Doc</a>
     */
    public OkxRestResponse<Map<String, Object>> getPositions(String instType, String instId) {
        return execute(okxApi.getPositions(instType, instId), "Get positions failed");
    }

    /**
     * 获取账户配置 (Get Account Configuration)
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-account-configuration">API Doc</a>
     */
    public OkxRestResponse<OkxAccountConfig> getAccountConfig() {
        return execute(okxApi.getAccountConfig(), "Get account config failed");
    }

    /**
     * 获取最大可买/卖数量 (Get Maximum Buy/Sell Amount)
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-maximum-buy-sell-amount-or-open-amount">API Doc</a>
     */
    public OkxRestResponse<OkxMaxSize> getMaxSize(Map<String, Object> queryMap) {
        return execute(okxApi.getMaxSize(queryMap), "Get max size failed");
    }

    /**
     * 查看账户持仓风险 (Get Position Risk)
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-position-risk">API Doc</a>
     */
    public OkxRestResponse<OkxPositionRisk> getPositionRisk(Map<String, Object> queryMap) {
        return execute(okxApi.getPositionRisk(queryMap), "Get position risk failed");
    }

    /**
     * 账单流水查询 (Get Bills Details (Recent 7 Days))
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-bills-details-recent-7-days">API Doc</a>
     */
    public OkxRestResponse<OkxBill> getBills(Map<String, Object> queryMap) {
        return execute(okxApi.getBills(queryMap), "Get bills failed");
    }

    /**
     * 查看账单流水（近3个月） (Get Bills Details (Recent 3 Months))
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-bills-details-recent-3-months">API Doc</a>
     */
    public OkxRestResponse<OkxBill> getBillsArchive(Map<String, Object> queryMap) {
        return execute(okxApi.getBillsArchive(queryMap), "Get bills archive failed");
    }

    /**
     * 获取最大可用数量 (Get Maximum Available Tradable Amount)
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-maximum-available-tradable-amount">API Doc</a>
     */
    public OkxRestResponse<OkxMaxAvailSize> getMaxAvailSize(Map<String, Object> queryMap) {
        return execute(okxApi.getMaxAvailSize(queryMap), "Get max avail size failed");
    }

    /**
     * 查看费率 (Get Fee Rates)
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-fee-rates">API Doc</a>
     */
    public OkxRestResponse<OkxTradeFee> getTradeFee(String instType, String instId, String uly, String category) {
        Map<String, Object> queryMap = new HashMap<>();
        if (instType != null) queryMap.put("instType", instType);
        if (instId != null) queryMap.put("instId", instId);
        if (uly != null) queryMap.put("uly", uly);
        if (category != null) queryMap.put("category", category);
        return execute(okxApi.getTradeFee(queryMap), "Get trade fee failed");
    }

    /**
     * 获取计息记录 (Get Interest Accrued)
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-interest-accrued">API Doc</a>
     */
    public OkxRestResponse<OkxInterestAccrued> getInterestAccrued(Map<String, Object> queryMap) {
        return execute(okxApi.getInterestAccrued(queryMap), "Get interest accrued failed");
    }

    /**
     * 获取借币利率 (Get Interest Rate)
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-interest-rate">API Doc</a>
     */
    public OkxRestResponse<OkxInterestRate> getInterestRate(Map<String, Object> queryMap) {
        return execute(okxApi.getInterestRate(queryMap), "Get interest rate failed");
    }

    /**
     * 获取最大可借 (Get Maximum Loan Amount)
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-maximum-loan-amount">API Doc</a>
     */
    public OkxRestResponse<OkxMaxLoan> getMaxLoan(Map<String, Object> queryMap) {
        return execute(okxApi.getMaxLoan(queryMap), "Get max loan failed");
    }

    /**
     * 设置持仓模式 (Set Position Mode)
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-set-position-mode">API Doc</a>
     */
    public OkxRestResponse<OkxPositionMode> setPositionMode(String posMode) {
        Map<String, Object> req = new HashMap<>();
        req.put("posMode", posMode);
        return execute(okxApi.setPositionMode(req), "Set position mode failed");
    }

    /**
     * 查看杠杆倍数 (Get Leverage)
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-leverage">API Doc</a>
     */
    public OkxRestResponse<OkxLeverageInfo> getLeverageInfo(String instId, String mgnMode) {
        Map<String, Object> queryMap = new HashMap<>();
        queryMap.put("instId", instId);
        queryMap.put("mgnMode", mgnMode);
        return execute(okxApi.getLeverageInfo(queryMap), "Get leverage info failed");
    }
}
