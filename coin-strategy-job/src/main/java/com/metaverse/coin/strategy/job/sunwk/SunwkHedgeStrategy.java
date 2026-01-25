package com.metaverse.coin.strategy.job.sunwk;

import com.metaverse.coin.strategy.common.client.sunwk.SunwkApiClientFactory;
import com.metaverse.coin.strategy.common.client.sunwk.SunwkApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sunwk 对冲策略实现（自动开双向单、平双向单）
 */
@Slf4j
@Component
public class SunwkHedgeStrategy {

    private final SunwkApiService sunwkApiService;
    private final ExecutorService executorService;

    public SunwkHedgeStrategy(
            @Value("${sunwk.api.base-url}") String baseUrl,
            @Value("${sunwk.api.api-key}") String apiKey,
            @Value("${sunwk.api.secret-key}") String secretKey) {
        
        if ("YOUR_API_KEY_HERE".equals(apiKey) || "YOUR_SECRET_KEY_HERE".equals(secretKey)) {
            log.warn("Sunwk API Key/Secret is not configured in application.yml");
        }

        // 初始化 API 客户端
        SunwkApiClientFactory factory = new SunwkApiClientFactory(baseUrl, apiKey, secretKey);
        this.sunwkApiService = factory.create();
        // 创建线程池用于并发操作
        this.executorService = Executors.newFixedThreadPool(10);
    }

    /**
     * 同时开多单和空单
     *
     * @param contractCode 合约代码 (e.g., "BTC-USDT")
     * @param quantity     开仓数量
     */
    public void openDualPositions(String contractCode, String quantity) {
        log.info("开始执行双向开仓: contract={}, quantity={}", contractCode, quantity);

        // 0. 风控检查 (余额、持仓限制等)
        if (!checkPreConditions(contractCode)) {
            log.warn("风控检查未通过，取消开仓");
            return;
        }

        // 1. 构建开多单参数 (根据实际 API 文档调整 Key)
        Map<String, Object> openLongParams = new HashMap<>();
        openLongParams.put("contract_code", contractCode);
        openLongParams.put("direction", "buy");        // 买入
        openLongParams.put("offset", "open");          // 开仓
        openLongParams.put("volume", quantity);        // 数量
        openLongParams.put("order_price_type", "limit"); // 限价或市价
        openLongParams.put("price", "50000");          // 示例价格，市价单可能不需要
        // openLongParams.put("lever_rate", 10);       // 杠杆倍数

        // 2. 构建开空单参数
        Map<String, Object> openShortParams = new HashMap<>();
        openShortParams.put("contract_code", contractCode);
        openShortParams.put("direction", "sell");      // 卖出
        openShortParams.put("offset", "open");         // 开仓
        openShortParams.put("volume", quantity);       // 数量
        openShortParams.put("order_price_type", "limit");
        openShortParams.put("price", "50000");

        // 3. 并发执行下单
        CompletableFuture<Void> longFuture = CompletableFuture.runAsync(() -> {
            try {
                String result = sunwkApiService.getOrderService().placeOrder(openLongParams);
                log.info("开多单结果: {}", result);
            } catch (Exception e) {
                log.error("开多单失败", e);
            }
        }, executorService);

        CompletableFuture<Void> shortFuture = CompletableFuture.runAsync(() -> {
            try {
                String result = sunwkApiService.getOrderService().placeOrder(openShortParams);
                log.info("开空单结果: {}", result);
            } catch (Exception e) {
                log.error("开空单失败", e);
            }
        }, executorService);

        // 等待两个操作完成
        CompletableFuture.allOf(longFuture, shortFuture).join();
        log.info("双向开仓操作完成");
    }

    /**
     * 同时平多单和空单
     *
     * @param contractCode 合约代码
     * @param quantity     平仓数量 (如果需要全平，建议先查询持仓)
     */
    public void closeDualPositions(String contractCode, String quantity) {
        log.info("开始执行双向平仓: contract={}, quantity={}", contractCode, quantity);

        // 1. 构建平多单参数 (卖出平多)
        Map<String, Object> closeLongParams = new HashMap<>();
        closeLongParams.put("contract_code", contractCode);
        closeLongParams.put("direction", "sell");      // 卖出
        closeLongParams.put("offset", "close");        // 平仓
        closeLongParams.put("volume", quantity);

        // 2. 构建平空单参数 (买入平空)
        Map<String, Object> closeShortParams = new HashMap<>();
        closeShortParams.put("contract_code", contractCode);
        closeShortParams.put("direction", "buy");      // 买入
        closeShortParams.put("offset", "close");       // 平仓
        closeShortParams.put("volume", quantity);

        // 3. 并发执行
        CompletableFuture<Void> closeLongFuture = CompletableFuture.runAsync(() -> {
            try {
                String result = sunwkApiService.getOrderService().placeOrder(closeLongParams);
                log.info("平多单结果: {}", result);
            } catch (Exception e) {
                log.error("平多单失败", e);
            }
        }, executorService);

        CompletableFuture<Void> closeShortFuture = CompletableFuture.runAsync(() -> {
            try {
                String result = sunwkApiService.getOrderService().placeOrder(closeShortParams);
                log.info("平空单结果: {}", result);
            } catch (Exception e) {
                log.error("平空单失败", e);
            }
        }, executorService);

        CompletableFuture.allOf(closeLongFuture, closeShortFuture).join();
        log.info("双向平仓操作完成");
    }

    private boolean checkPreConditions(String contractCode) {
        try {
            // 简单检查：能否获取到资产信息
            String assets = sunwkApiService.getAccountService().getAssets();
            log.info("当前资产信息: {}", assets);
            // TODO: 解析 JSON 判断余额是否充足
            // if (balance < required) return false;
            return true;
        } catch (Exception e) {
            log.error("获取资产信息失败", e);
            return false;
        }
    }
}
