package com.metaverse.coin.strategy.common.chain.evm.enums;

import lombok.Getter;

/**
 * 链枚举
 */
@Getter
public enum Chain {
    ARB(42161, "ETH", 18),
    BSC_TEST(97, "BNB", 18),
            ;

    public final Integer chainId;
    public final String coin;
    public final Integer decimal;

    Chain(Integer chainId, String coin, Integer decimal) {
        this.chainId = chainId;
        this.coin = coin;
        this.decimal = decimal;
    }

    public static Chain getByName(String name) {

        for (Chain item : Chain.values()) {
            if (item.name().equalsIgnoreCase(name.trim())) {
                return item;
            }
        }

        return null;
    }

    public static Chain getById(int chainId) {
        for (Chain item : Chain.values()) {
            if (item.chainId == chainId) {
                return item;
            }
        }
        return null;
    }
}
