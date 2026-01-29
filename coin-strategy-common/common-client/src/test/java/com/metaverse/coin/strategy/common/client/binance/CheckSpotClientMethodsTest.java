package com.metaverse.coin.strategy.common.client.binance;

import com.binance.connector.client.SpotClient;
import com.binance.connector.client.impl.SpotClientImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

public class CheckSpotClientMethodsTest {

    @Test
    public void printMethods() {
        SpotClient client = new SpotClientImpl("api", "secret");
        System.out.println("Methods of SpotClient:");
        for (Method method : SpotClient.class.getMethods()) {
            System.out.println(method.getName());
        }
        System.out.println("Methods of SpotClientImpl:");
        for (Method method : SpotClientImpl.class.getMethods()) {
            System.out.println(method.getName());
        }
    }
}
