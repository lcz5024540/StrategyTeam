package com.metaverse.coin.strategy.common.client.binance.debug;

import com.binance.connector.client.impl.SpotClientImpl;
import com.binance.connector.futures.client.impl.UMFuturesClientImpl;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Base class for Binance Debug/Integration Tests.
 * <br>
 * How to use:
 * 1. Create a file named `binance-test.properties` in `src/test/resources/`.
 * 2. Add your keys:
 *    binance.api.key=YOUR_API_KEY
 *    binance.secret.key=YOUR_SECRET_KEY
 * 3. Or use Environment Variables: BINANCE_API_KEY, BINANCE_SECRET_KEY.
 * <br>
 * <b>WARNING: These tests execute REAL TRADES. Use with caution!</b>
 */
public class BinanceDebugBase {

    protected static String API_KEY;
    protected static String SECRET_KEY;
    
    protected static final String TESTNET_BASE_URL = "https://api.binance.com/api";
    protected static final String SPOT_TESTNET_BASE_URL = "https://api4.binance.com/api";

    protected SpotClientImpl spotClient;
    protected UMFuturesClientImpl futuresClient;

    static {
        loadCredentials();
    }

    private static void loadCredentials() {
        // 1. Try loading from properties file
        try (InputStream input = BinanceDebugBase.class.getClassLoader().getResourceAsStream("binance-test.properties")) {
            if (input != null) {
                Properties prop = new Properties();
                prop.load(input);
                API_KEY = prop.getProperty("binance.api.key");
                SECRET_KEY = prop.getProperty("binance.secret.key");
            }
        } catch (IOException ex) {
            System.err.println("Could not load binance-test.properties: " + ex.getMessage());
        }

        // 2. Fallback to System Properties or Environment Variables
        if (API_KEY == null) {
            API_KEY = System.getProperty("binance.api.key", System.getenv("BINANCE_API_KEY"));
        }
        if (SECRET_KEY == null) {
            SECRET_KEY = System.getProperty("binance.secret.key", System.getenv("BINANCE_SECRET_KEY"));
        }
    }

    @BeforeEach
    public void setUp() {
        // Initialize clients with keys (if provided)
        if (API_KEY != null && SECRET_KEY != null) {
            spotClient = new SpotClientImpl(API_KEY, SECRET_KEY, SPOT_TESTNET_BASE_URL);
            futuresClient = new UMFuturesClientImpl(API_KEY, SECRET_KEY, TESTNET_BASE_URL);
        } else {
            // Fallback for public endpoints or when keys are missing (will fail for auth endpoints)
            spotClient = new SpotClientImpl(SPOT_TESTNET_BASE_URL);
            futuresClient = new UMFuturesClientImpl(TESTNET_BASE_URL);
        }
    }
}
