package com.metaverse.coin.strategy.common.model.binance.entity.wallet;

import lombok.Data;
import java.util.List;

@Data
public class BinanceCoinInfo {
    private String coin;
    private Boolean depositAllEnable;
    private String free;
    private String freeze;
    private String ipoable;
    private String ipoing;
    private Boolean isLegalMoney;
    private String locked;
    private String name;
    private List<Network> networkList;
    private String storage;
    private Boolean trading;
    private Boolean withdrawAllEnable;
    private String withdrawing;

    @Data
    public static class Network {
        private String addressRegex;
        private String coin;
        private String depositDesc;
        private Boolean depositEnable;
        private Boolean isDefault;
        private String memoRegex;
        private Integer minConfirm;
        private String name;
        private String network;
        private Boolean resetAddressStatus;
        private String specialTips;
        private Integer unLockConfirm;
        private String withdrawDesc;
        private Boolean withdrawEnable;
    }
}
