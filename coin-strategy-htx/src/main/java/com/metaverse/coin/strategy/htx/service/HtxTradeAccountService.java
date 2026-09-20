package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.config.HtxSecretDecryptor;
import com.metaverse.coin.strategy.htx.config.HtxTrendProperties;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTradeAccountEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTradeAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Loads HTX trading account credentials from DB and builds account-scoped clients. */
@Slf4j
@Service
@RequiredArgsConstructor
public class HtxTradeAccountService {
    private static final String PRIMARY = "primary";

    private final HtxTradeAccountMapper mapper;
    private final HtxSecretDecryptor decryptor;

    public HtxUsdtSwapApiService primarySwapApiService(HtxTradeProperties tradeProperties,
                                                        HtxContractProperties contractProperties) {
        Optional<HtxTradeAccountEntity> account = primaryTrendAccount();
        if (account.isPresent() && hasCredentials(account.get())) {
            HtxTradeAccountEntity entity = account.get();
            log.info("[HTX-ACCOUNT] using DB primary trend account label={}", label(entity));
            return swapApi(entity, contractProperties);
        }
        if (tradeProperties != null && tradeProperties.getApi() != null
                && StringUtils.hasText(tradeProperties.getApi().getAccessKey())
                && StringUtils.hasText(tradeProperties.getApi().getSecretKey())) {
            log.warn("[HTX-ACCOUNT] DB primary trend account missing credentials; falling back to htx.api env credentials");
            return new HtxUsdtSwapApiService(
                    decryptor.decryptIfEncrypted(tradeProperties.getApi().getAccessKey()),
                    decryptor.decryptIfEncrypted(tradeProperties.getApi().getSecretKey()),
                    contractProperties.getBaseUrl(),
                    contractProperties.getUidQueryLimit(),
                    contractProperties.getUidTradeLimit(),
                    contractProperties.getUidRateWindowMs());
        }
        throw new IllegalStateException("HTX trend primary account is not configured in DB or environment.");
    }

    public List<HtxTrendMirrorAccount> trendMirrorAccounts(HtxTrendProperties trendProperties,
                                                            HtxContractProperties contractProperties) {
        List<HtxTrendMirrorAccount> accounts = new ArrayList<>();
        for (HtxTradeAccountEntity entity : dbMirrorAccounts()) {
            if (!hasCredentials(entity)) {
                log.warn("[HTX-ACCOUNT] skip mirror account without credentials label={}", label(entity));
                continue;
            }
            accounts.add(mirrorAccount(entity, trendProperties, contractProperties));
        }
        if (!accounts.isEmpty()) {
            return accounts;
        }
        HtxTrendProperties.Mirror mirror = trendProperties == null ? null : trendProperties.getMirror();
        if (mirror != null && mirror.isEnabled()
                && StringUtils.hasText(mirror.getAccessKey()) && StringUtils.hasText(mirror.getSecretKey())) {
            log.warn("[HTX-ACCOUNT] DB mirror accounts empty; falling back to legacy htx.trend.mirror env account={}",
                    StringUtils.hasText(mirror.getAccountLabel()) ? mirror.getAccountLabel() : "mirror");
            HtxUsdtSwapApiService api = new HtxUsdtSwapApiService(
                    decryptor.decryptIfEncrypted(mirror.getAccessKey()),
                    decryptor.decryptIfEncrypted(mirror.getSecretKey()),
                    StringUtils.hasText(mirror.getBaseUrl()) ? mirror.getBaseUrl() : contractProperties.getBaseUrl(),
                    contractProperties.getUidQueryLimit(),
                    contractProperties.getUidTradeLimit(),
                    contractProperties.getUidRateWindowMs());
            String accountLabel = StringUtils.hasText(mirror.getAccountLabel()) ? mirror.getAccountLabel().trim() : "mirror";
            accounts.add(new HtxTrendMirrorAccount(accountLabel, api.getSwapService(),
                    new HtxSwapFeeRateResolver(api.getSwapService(), trendProperties::getEstimatedTakerFeeRate,
                            trendProperties::getFeeRateCacheMs, accountLabel),
                    mirror.isSyncLeverage(), mirror.isBlockEntryOnLeverageSyncFailure(),
                    mirror.isBlockPrimaryCloseUntilSynced()));
        }
        return accounts;
    }

    private Optional<HtxTradeAccountEntity> primaryTrendAccount() {
        try {
            return Optional.ofNullable(mapper.findPrimaryTrendAccount());
        } catch (DataAccessException exception) {
            log.warn("[HTX-ACCOUNT] primary account query failed; env fallback may be used: {}",
                    exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private List<HtxTradeAccountEntity> dbMirrorAccounts() {
        try {
            return mapper.findTrendMirrorAccounts();
        } catch (DataAccessException exception) {
            log.warn("[HTX-ACCOUNT] mirror account query failed; env fallback may be used: {}",
                    exception.getClass().getSimpleName());
            return List.of();
        }
    }

    private HtxTrendMirrorAccount mirrorAccount(HtxTradeAccountEntity entity,
                                                HtxTrendProperties trendProperties,
                                                HtxContractProperties contractProperties) {
        HtxUsdtSwapApiService api = swapApi(entity, contractProperties);
        String accountLabel = label(entity);
        return new HtxTrendMirrorAccount(accountLabel, api.getSwapService(),
                new HtxSwapFeeRateResolver(api.getSwapService(), trendProperties::getEstimatedTakerFeeRate,
                        trendProperties::getFeeRateCacheMs, accountLabel),
                flag(entity.getSyncLeverage(), true),
                flag(entity.getBlockEntryOnLeverageSyncFailure(), false),
                flag(entity.getBlockPrimaryCloseUntilSynced(), true));
    }

    private HtxUsdtSwapApiService swapApi(HtxTradeAccountEntity entity, HtxContractProperties contractProperties) {
        return new HtxUsdtSwapApiService(
                decryptor.decryptIfEncrypted(entity.getApiKeyCipher()),
                decryptor.decryptIfEncrypted(entity.getSecretKeyCipher()),
                StringUtils.hasText(entity.getBaseUrl()) ? entity.getBaseUrl() : contractProperties.getBaseUrl(),
                contractProperties.getUidQueryLimit(),
                contractProperties.getUidTradeLimit(),
                contractProperties.getUidRateWindowMs());
    }

    private static boolean hasCredentials(HtxTradeAccountEntity entity) {
        return entity != null && StringUtils.hasText(entity.getApiKeyCipher())
                && StringUtils.hasText(entity.getSecretKeyCipher());
    }

    private static String label(HtxTradeAccountEntity entity) {
        if (entity == null || !StringUtils.hasText(entity.getAccountLabel())) {
            return PRIMARY;
        }
        return entity.getAccountLabel().trim();
    }

    private static boolean flag(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }
}
