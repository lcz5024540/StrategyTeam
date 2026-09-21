package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Venue-native names for the locked BTC/ETH universe on Binance and HTX.
 *
 * <p>Perpetual public clients on this branch still take a canonical {@code BTCUSDT}
 * style key; HTX spot uses lowercase {@code btcusdt}.</p>
 */
public record SpotPerpInstrument(SpotPerpVenue venue, SpotPerpAsset asset) {

    public enum SpotPerpVenue {
        BINANCE, HTX
    }

    public enum SpotPerpAsset {
        BTC, ETH
    }

    public static List<SpotPerpInstrument> universe() {
        List<SpotPerpInstrument> instruments = new ArrayList<>();
        for (SpotPerpVenue venue : SpotPerpVenue.values()) {
            for (SpotPerpAsset asset : SpotPerpAsset.values()) {
                instruments.add(new SpotPerpInstrument(venue, asset));
            }
        }
        return List.copyOf(instruments);
    }

    public String canonicalSymbol() {
        return asset.name() + "USDT";
    }

    /** Venue-native spot symbol used on public REST books. */
    public String spotSymbol() {
        return switch (venue) {
            case BINANCE -> asset.name() + "USDT";
            case HTX -> asset.name().toLowerCase(Locale.ROOT) + "usdt";
        };
    }

    /** Venue-native USDT-margined perpetual symbol. */
    public String perpExchangeSymbol() {
        return switch (venue) {
            case BINANCE -> asset.name() + "USDT";
            case HTX -> asset.name() + "-USDT";
        };
    }

    public String key() {
        return venue.name() + ":" + canonicalSymbol();
    }

    @Override
    public String toString() {
        return key();
    }
}
