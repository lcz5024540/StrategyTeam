package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTradeAccountFeeProfileEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTradeAccountFeeProfileMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTradeAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

/** Persists only fee-schedule changes; the current profile is the source of future cost provenance. */
@Slf4j
@Component
@RequiredArgsConstructor
public class HtxAccountFeeProfileRecorder {
    static final String EXCHANGE_HTX = "HTX";
    static final String MARKET_USDT_SWAP = "USDT_SWAP";
    static final String SOURCE_HTX_ACCOUNT = "HTX_ACCOUNT";
    static final String SOURCE_CONFIGURED_FALLBACK = "CONFIGURED_FALLBACK";

    private final HtxTradeAccountMapper accountMapper;
    private final HtxTradeAccountFeeProfileMapper profileMapper;

    @Transactional
    public synchronized Long record(String accountLabel, String contractCode, HtxSwapFeeRateResolver.FeeRates rates) {
        if (!StringUtils.hasText(accountLabel) || !StringUtils.hasText(contractCode) || rates == null) {
            return null;
        }
        try {
            Long accountId = accountMapper.findIdByAccountLabel(accountLabel.trim());
            if (accountId == null) {
                log.warn("[HTX-FEE-PROFILE] skip unregistered account={} contract={}", accountLabel, contractCode);
                return null;
            }
            String normalizedContract = contractCode.trim().toUpperCase(Locale.ROOT);
            LocalDateTime observedAt = LocalDateTime.now();
            HtxTradeAccountFeeProfileEntity current = profileMapper.findCurrent(accountId, MARKET_USDT_SWAP,
                    normalizedContract);
            if (sameSchedule(current, rates)) {
                profileMapper.touchCurrent(current.getId(), observedAt);
                return current.getId();
            }
            if (current != null) {
                profileMapper.closeCurrent(current.getId(), observedAt);
            }
            HtxTradeAccountFeeProfileEntity next = new HtxTradeAccountFeeProfileEntity();
            next.setAccountId(accountId);
            next.setAccountLabel(accountLabel.trim());
            next.setExchange(EXCHANGE_HTX);
            next.setMarketType(MARKET_USDT_SWAP);
            next.setContractCode(normalizedContract);
            next.setOpenMakerRate(rates.openMaker());
            next.setOpenTakerRate(rates.openTaker());
            next.setCloseMakerRate(rates.closeMaker());
            next.setCloseTakerRate(rates.closeTaker());
            next.setEffectiveTakerRate(rates.taker());
            next.setSource(rates.fallback() ? SOURCE_CONFIGURED_FALLBACK : SOURCE_HTX_ACCOUNT);
            next.setFallback(rates.fallback());
            next.setCurrentProfileKey(currentProfileKey(accountId, normalizedContract));
            next.setEffectiveFrom(observedAt);
            next.setLastObservedAt(observedAt);
            profileMapper.insert(next);
            log.info("[HTX-FEE-PROFILE] account={} contract={} profileId={} source={} fallback={}",
                    accountLabel, normalizedContract, next.getId(), next.getSource(), next.getFallback());
            return next.getId();
        } catch (DataAccessException exception) {
            log.warn("[HTX-FEE-PROFILE] persist failed account={} contract={} error={}", accountLabel, contractCode,
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean sameSchedule(HtxTradeAccountFeeProfileEntity current,
                                        HtxSwapFeeRateResolver.FeeRates rates) {
        return current != null
                && same(current.getOpenMakerRate(), rates.openMaker())
                && same(current.getOpenTakerRate(), rates.openTaker())
                && same(current.getCloseMakerRate(), rates.closeMaker())
                && same(current.getCloseTakerRate(), rates.closeTaker())
                && same(current.getEffectiveTakerRate(), rates.taker())
                && Boolean.TRUE.equals(current.getFallback()) == rates.fallback()
                && (rates.fallback() ? SOURCE_CONFIGURED_FALLBACK : SOURCE_HTX_ACCOUNT).equals(current.getSource());
    }

    private static boolean same(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private static String currentProfileKey(long accountId, String contractCode) {
        return "active:" + accountId + ":" + MARKET_USDT_SWAP + ":" + contractCode;
    }
}
