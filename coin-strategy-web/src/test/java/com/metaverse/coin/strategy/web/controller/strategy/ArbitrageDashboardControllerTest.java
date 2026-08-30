package com.metaverse.coin.strategy.web.controller.strategy;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ArbitrageDashboardControllerTest {

    @Test
    void exposesOnlyEightReadOnlyEndpoints() {
        Method[] methods = ArbitrageDashboardController.class.getDeclaredMethods();
        long getEndpoints = Arrays.stream(methods).filter(method -> method.isAnnotationPresent(GetMapping.class)).count();
        boolean hasPostEndpoint = Arrays.stream(methods).anyMatch(method -> method.isAnnotationPresent(PostMapping.class));

        assertEquals(8, getEndpoints);
        assertFalse(hasPostEndpoint);
    }

    @Test
    void usesDashboardReadModelBasePath() {
        RequestMapping mapping = ArbitrageDashboardController.class.getAnnotation(RequestMapping.class);
        assertEquals("/strategy-platform/arbitrage-dashboard", mapping.value()[0]);
    }
}
