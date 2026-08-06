package com.metaverse.coin.strategy.common.client.htx.model;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 复现并锁定 HTX depth 解析：/market/depth?type=step0 的每档是二元数组 [price, amount]，
 * 数据在 tick 下。此前 HtxPriceLevel 为具名字段 bean，fastjson2 默认不做数组→bean 位置映射，
 * price 解析为 null，被 HtxDepthGuard 误判为 "ordering violation" 而永远拒交。
 * 用与生产完全一致的解析路径（HtxRestClient.execute: 补 status=ok 后 json.to(TypeReference)）。
 */
class HtxDepthParseTest {

    // 取自 api.huobi.pro 真实响应（bids 降序 / asks 升序）
    private static final String REAL_DEPTH =
            "{\"ch\":\"market.btcusdt.depth.step0\",\"status\":\"ok\",\"ts\":1786008430843," +
            "\"tick\":{\"ts\":1786008430000,\"version\":192405845611," +
            "\"bids\":[[64802.82,1.180616],[64799.99,0.002],[64796.93,0.022998],[64796.92,0.077044],[64796.5,0.396918]]," +
            "\"asks\":[[64802.83,1.210778],[64804.6,1.064814],[64808.01,0.002424],[64813.3,0.002689],[64814.97,0.023094]]}}";

    @Test
    void depth_array_levels_parse_price_and_amount() {
        JSONObject json = JSON.parseObject(REAL_DEPTH);
        HtxRestResponse<HtxDepth> resp =
                json.to(new TypeReference<HtxRestResponse<HtxDepth>>() {}.getType());

        assertTrue(resp.ok());
        HtxDepth depth = resp.payload();
        assertNotNull(depth, "tick must map to payload");

        List<HtxPriceLevel> bids = depth.getBids();
        List<HtxPriceLevel> asks = depth.getAsks();
        assertEquals(5, bids.size());
        assertEquals(5, asks.size());

        // 位置映射：index0=price, index1=amount，且非 null
        assertEquals(new BigDecimal("64802.82"), bids.get(0).getPrice());
        assertEquals(new BigDecimal("1.180616"), bids.get(0).getAmount());
        assertEquals(new BigDecimal("64802.83"), asks.get(0).getPrice());
        assertEquals(new BigDecimal("1.210778"), asks.get(0).getAmount());

        // 顺序：bids 严格降序、asks 严格升序（守卫依赖此不变量）
        for (int i = 1; i < bids.size(); i++) {
            assertTrue(bids.get(i).getPrice().compareTo(bids.get(i - 1).getPrice()) < 0, "bids must be strictly descending");
        }
        for (int i = 1; i < asks.size(); i++) {
            assertTrue(asks.get(i).getPrice().compareTo(asks.get(i - 1).getPrice()) > 0, "asks must be strictly ascending");
        }
    }
}
