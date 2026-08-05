package com.metaverse.coin.strategy.htx.util;

import java.util.List;

/**
 * 拆分 HTX symbol 为 base/quote。HTX symbol 全小写无分隔（btcusdt）。
 * 按已知 quote 后缀由长到短匹配，避免 "btc" 抢先 "busd" 等歧义。
 */
public final class HtxSymbolUtil {
    // 长后缀优先，规避 usdt vs usd、husd vs usd 歧义。
    private static final List<String> QUOTES = List.of(
            "usdt", "usdc", "husd", "usdd", "tusd", "dai", "eth", "btc", "trx", "ht");

    private HtxSymbolUtil() {}

    public static String quote(String symbol) {
        if (symbol == null || symbol.isEmpty()) return "";
        String s = symbol.toLowerCase();
        for (String q : QUOTES) {
            if (s.length() > q.length() && s.endsWith(q)) return q;
        }
        return "";
    }

    public static String base(String symbol) {
        if (symbol == null || symbol.isEmpty()) return "";
        String s = symbol.toLowerCase();
        String q = quote(s);
        return q.isEmpty() ? s : s.substring(0, s.length() - q.length());
    }
}
