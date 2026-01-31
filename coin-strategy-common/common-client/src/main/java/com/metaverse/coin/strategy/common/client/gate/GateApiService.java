package com.metaverse.coin.strategy.common.client.gate;

import com.metaverse.coin.strategy.common.client.gate.service.*;
import io.gate.gateapi.ApiClient;
import io.gate.gateapi.api.AccountApi;
import io.gate.gateapi.api.DeliveryApi;
import io.gate.gateapi.api.FuturesApi;
import io.gate.gateapi.api.SpotApi;
import io.gate.gateapi.api.WalletApi;
import lombok.Getter;

/**
 * Gate.io API Service Facade
 * <p>
 * Provides a unified entry point for accessing Gate.io REST APIs.
 * This class encapsulates the official Gate.io SDK's ApiClient and specific API interfaces (Spot, Futures, Wallet, etc.),
 * exposing higher-level services for Market Data, Account, Trade, and Wallet operations.
 * </p>
 */
@Getter
public class GateApiService {

    private final ApiClient apiClient;

    private final SpotApi spotApi;
    private final FuturesApi futuresApi;
    private final DeliveryApi deliveryApi;
    private final WalletApi walletApi;
    private final AccountApi accountApi;

    /**
     * Account Service: Spot and Futures account information.
     */
    private final GateAccountService accountService;

    /**
     * Trade Service: Order placement and cancellation for Spot and Futures.
     */
    private final GateTradeService tradeService;

    /**
     * Market Data Service: Tickers and public market data.
     */
    private final GateMarketDataService marketDataService;

    /**
     * Wallet Service: Total balance and wallet related operations.
     */
    private final GateWalletService walletService;

    /**
     * Constructor using default base URL (https://api.gateio.ws/api/v4).
     *
     * @param apiKey    Gate.io API Key
     * @param secretKey Gate.io Secret Key
     */
    public GateApiService(String apiKey, String secretKey) {
        this(apiKey, secretKey, "https://api.gateio.ws/api/v4");
    }

    /**
     * Constructor with custom base URL.
     *
     * @param apiKey    Gate.io API Key
     * @param secretKey Gate.io Secret Key
     * @param basePath  Custom API Base URL (e.g., for testnet)
     */
    public GateApiService(String apiKey, String secretKey, String basePath) {
        this.apiClient = new ApiClient();
        this.apiClient.setBasePath(basePath);
        this.apiClient.setApiKeySecret(apiKey, secretKey);

        this.spotApi = new SpotApi(apiClient);
        this.futuresApi = new FuturesApi(apiClient);
        this.deliveryApi = new DeliveryApi(apiClient);
        this.walletApi = new WalletApi(apiClient);
        this.accountApi = new AccountApi(apiClient);

        this.accountService = new GateAccountService(spotApi, futuresApi);
        this.tradeService = new GateTradeService(spotApi, futuresApi);
        this.marketDataService = new GateMarketDataService(spotApi, futuresApi);
        this.walletService = new GateWalletService(walletApi);
    }
}
