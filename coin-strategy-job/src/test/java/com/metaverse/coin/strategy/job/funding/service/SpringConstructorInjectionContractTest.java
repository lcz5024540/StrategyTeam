package com.metaverse.coin.strategy.job.funding.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpringConstructorInjectionContractTest {
    @Test
    void springManagedFundingComponentsWithTestConstructorsDeclareInjectionConstructor() {
        List<Class<?>> classesWithTestConstructors = List.of(
                CrossExchangeAutoPaperOpenJob.class,
                CrossExchangeFundingReplayService.class,
                CrossExchangeFundingScanJob.class,
                CrossExchangeFundingSettlementJob.class,
                CrossExchangeMarketSnapshotRecorder.class,
                CrossExchangePaperPnlService.class,
                CrossExchangePaperPnlSnapshotJob.class);

        for (Class<?> componentClass : classesWithTestConstructors) {
            long injectableConstructors = Arrays.stream(componentClass.getDeclaredConstructors())
                    .filter(constructor -> !Modifier.isPrivate(constructor.getModifiers()))
                    .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                    .count();

            assertEquals(1, injectableConstructors,
                    () -> componentClass.getSimpleName()
                            + " must declare exactly one @Autowired constructor when it also has a"
                            + " package-private test constructor");
        }
    }
}
