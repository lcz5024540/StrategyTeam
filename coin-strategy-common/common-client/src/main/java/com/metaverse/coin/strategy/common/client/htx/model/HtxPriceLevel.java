package com.metaverse.coin.strategy.common.client.htx.model;

import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.annotation.JSONField;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A single price level in HTX order book.
 * Amount is expressed in base asset (e.g. BTC in BTCUSDT).
 * <p>
 * HTX 的 /market/depth 每档是二元数组 {@code [price, amount]}，非对象。fastjson2 用
 * {@link JSONReader.Feature#SupportArrayToBean} 做数组→bean 位置映射，但默认按字段字母序
 * （amount 在 price 前）会把两列填反。用 {@link JSONField#ordinal()} 显式固定顺序
 * price(index0), amount(index1)；否则 price/amount 错位，被 HtxDepthGuard 误判 ordering violation 而拒交。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JSONType(deserializeFeatures = JSONReader.Feature.SupportArrayToBean)
public class HtxPriceLevel {
    @JSONField(ordinal = 1)
    private BigDecimal price;
    @JSONField(ordinal = 2)
    private BigDecimal amount;
}
