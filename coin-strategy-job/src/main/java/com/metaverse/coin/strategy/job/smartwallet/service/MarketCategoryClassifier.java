package com.metaverse.coin.strategy.job.smartwallet.service;

import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 市场类别的纯文本关键词分类器（无外部依赖、无 API 调用）。
 *
 * <p>修复一（6.2）：S2.5 入口新鲜度闸门早于市场元数据获取，此刻只有 {@code event.question}，
 * 因此把 {@link SmartWalletMarketStateService#resolveNormalizedCategory} 的关键词匹配段抽到这里，
 * 供 S2.5 与 {@code SmartWalletMarketStateService} 共同复用，避免逻辑重复。</p>
 *
 * <p>注意：本类只做关键词匹配（对应 {@code resolveNormalizedCategory} 的 Step 2）；
 * Polymarket {@code sourceCategory} 的直接映射（Step 1）仍由 {@code SmartWalletMarketStateService}
 * 保留，因为 S2.5 此时拿不到 {@code sourceCategory}。</p>
 */
public final class MarketCategoryClassifier {

    public static final String CATEGORY_POLITICS = "POLITICS";
    public static final String CATEGORY_WEATHER = "WEATHER";
    public static final String CATEGORY_SPORTS = "SPORTS";
    public static final String CATEGORY_CRYPTO = "CRYPTO";
    public static final String CATEGORY_MACRO = "MACRO";
    public static final String CATEGORY_BUSINESS = "BUSINESS";
    public static final String CATEGORY_ENTERTAINMENT = "ENTERTAINMENT";
    public static final String CATEGORY_CURRENT_EVENTS = "CURRENT_EVENTS";
    public static final String CATEGORY_OTHER = "OTHER";

    private MarketCategoryClassifier() {
    }

    /**
     * 按关键词把任意文本（通常是市场 question，或 {@code sourceCategory + " " + question}）
     * 归一化为标准类别。未命中任何关键词返回 {@link #CATEGORY_OTHER}。
     */
    public static String classifyByText(String text) {
        String combined = normalize(text);
        if (combined.isEmpty()) {
            return CATEGORY_OTHER;
        }
        if (containsAny(combined,
                // Crypto tokens & projects (comprehensive)
                "crypto", "cryptocurrency", "bitcoin", "ethereum", "solana",
                "btc", "eth", "sol", "xrp", "ripple", "doge", "dogecoin",
                "cardano", "bnb", "binance", "avax", "avalanche",
                "matic", "polygon", "chainlink", "polkadot",
                "sui", "aptos", "arb", "arbitrum", "optimism",
                "cosmos", "uniswap", "aave",
                "ltc", "litecoin", "bch", "filecoin", "hbar", "hedera",
                "xlm", "stellar", "algorand", "icp", "vechain",
                "fantom", "sandbox", "axie",
                "shib", "pepe", "floki", "bonk", "meme coin",
                "toncoin", "trx", "tron", "eos",
                "sei", "injective", "jupiter", "render", "rndr",
                "kaspa", "celestia", "stacks",
                "pendle", "pyth", "jto", "worldcoin",
                "blockchain", "defi", "nft", "stablecoin",
                "usdt", "usdc", "coinbase", "kraken",
                "mining", "halving", "altcoin", "memecoin",
                // Price-related patterns strongly indicating crypto
                "above $", "below $", "reach $", "hit $", "break $",
                "$100k", "$50k", "$10k", "$1k")) {
            return CATEGORY_CRYPTO;
        }
        if (containsAny(combined,
                "politic", "election", "president", "senate", "congress",
                "parliament", "governor", "mayor", "prime minister",
                "referendum", "vote", "democrat", "republican", "gop",
                "trump", "biden", "party", "ballot", "primary",
                "cabinet", "legislation", "bill pass", "veto",
                "supreme court", "executive order", "impeach")) {
            return CATEGORY_POLITICS;
        }
        if (containsAny(combined,
                "sports", "nba", "nfl", "mlb", "nhl", "mls",
                "soccer", "football", "baseball", "basketball", "tennis",
                "golf", "ufc", "mma", "boxing", "fifa", "f1", "formula 1",
                "champions league", "premier league", "la liga", "serie a",
                "bundesliga", "world cup", "super bowl", "playoffs",
                "game ", "match", "vs.", "vs ", " v ",
                // Common NBA/NFL/MLB teams & terms
                "lakers", "celtics", "warriors", "76ers", "sixers",
                "bucks", "nets", "knicks", "bulls", "heat", "nuggets",
                "chiefs", "eagles", "cowboys", "49ers", "ravens",
                "yankees", "dodgers", "braves", "astros", "mets",
                "mvp", "championship", "finals", "series",
                "o/u", "over/under", "spread", "point",
                // Soccer
                "real madrid", "barcelona", "manchester", "liverpool",
                "arsenal", "chelsea", "bayern", "psg", "juventus")) {
            return CATEGORY_SPORTS;
        }
        if (containsAny(combined,
                "weather", "rain", "snow", "temperature", "hurricane",
                "storm", "sunny", "wind", "climate", "tornado",
                "flood", "drought", "heatwave", "wildfire")) {
            return CATEGORY_WEATHER;
        }
        if (containsAny(combined,
                "macro", "cpi", "inflation", "gdp", "fed", "fomc",
                "recession", "unemployment", "interest rate", "treasury",
                "oil", "gold", "silver", "commodity", "forex",
                "bond", "yield", "tariff", "trade war", "sanctions",
                "central bank", "ecb", "boj", "rate cut", "rate hike",
                "jobs report", "nonfarm", "ppi", "pce")) {
            return CATEGORY_MACRO;
        }
        if (containsAny(combined,
                "business", "company", "earnings", "ipo", "merger",
                "acquisition", "ceo", "revenue", "stock", "shares",
                "tesla", "apple", "nvidia", "amazon", "google", "meta",
                "microsoft", "openai", "spacex", "tiktok",
                "market cap", "quarterly", "profit", "layoff",
                "ipo", "spac", "sec", "antitrust", "valuation")) {
            return CATEGORY_BUSINESS;
        }
        if (containsAny(combined,
                "entertainment", "movie", "film", "oscar", "grammy",
                "emmy", "music", "tv", "television", "celebrity",
                "box office", "streaming", "netflix", "disney",
                "album", "concert", "award", "billboard",
                "kardashian", "taylor swift", "drake")) {
            return CATEGORY_ENTERTAINMENT;
        }
        if (containsAny(combined,
                "news", "current event", "breaking", "world",
                "war", "conflict", "headline", "crisis",
                "earthquake", "eruption", "terrorist", "peace",
                "ceasefire", "treaty", "refugee", "nato", "un ")) {
            return CATEGORY_CURRENT_EVENTS;
        }
        return CATEGORY_OTHER;
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String haystack, String... needles) {
        if (!StringUtils.hasText(haystack) || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (StringUtils.hasText(needle) && haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
