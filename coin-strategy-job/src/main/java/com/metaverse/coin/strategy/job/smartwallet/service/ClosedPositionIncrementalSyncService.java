package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderAnalysisMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderAnalysisEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Polymarket /closed-positions 增量同步服务。
 *
 * 设计要点：
 * 1. 使用 sortBy=TIMESTAMP & sortDirection=DESC 拉取，最新订单在前。
 * 2. 首次全量：DESC 翻页，offset 0→N，直到 chunk.isEmpty() 或 stuck 检测命中。
 * 3. 增量：DESC 翻页，遇到"已存在 DB 中"的记录（或 closed_at ≤ 高水位）即停止。
 *    这样无论接口是否新增数据在列表头部，都不会漏抓，也不会重复扫描历史。
 * 4. Stuck 检测：相邻两页 (marketId, outcome, closed_at) 三元组完全一致则停止。
 * 5. 数据质量：正常完成 → TRUTH；遇 stuck → STUCK；中断/超时 → PARTIAL。
 * 6. HTTP 并发通过 Semaphore 限流，避免触发 Polymarket data-api 的 429。
 *
 * 本 Service 只负责同步逻辑本身，线程调度由 ClosedPositionSyncJob 或
 * SmartWalletLeaderTruthMaintenanceJob 决定。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ClosedPositionIncrementalSyncService {

    public static final String SOURCE = "POLYMARKET";
    public static final String QUALITY_TRUTH = "TRUTH";
    public static final String QUALITY_PARTIAL = "PARTIAL";
    public static final String QUALITY_STUCK = "STUCK";
    public static final String QUALITY_UNTRUSTED = "UNTRUSTED";

    private static final String SORT_BY_TIMESTAMP = "TIMESTAMP";
    private static final String SORT_DIRECTION_DESC = "DESC";

    private final PolymarketApiClient polymarketApiClient;
    private final SmartWalletOrderAnalysisMapper orderAnalysisMapper;
    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletProperties properties;
    private final ObjectMapper objectMapper;

    @Getter
    private volatile Semaphore httpSemaphore = new Semaphore(4);

    @PostConstruct
    void init() {
        int permits = safeInt(() -> properties.getPolymarket().getClosedPositionSync().getHttpConcurrencyLimit(), 4);
        this.httpSemaphore = new Semaphore(permits);
        log.info("[ClosedPositionSync] initialized httpConcurrencyLimit={} pageSize={} maxFullPages={} maxIncrementalPages={}",
                permits,
                config().getPageSize(),
                config().getMaxFullPages(),
                config().getMaxIncrementalPages());
    }

    /** 增量同步入口。遇到首条"已存在 DB 记录"立即停止。 */
    public SyncResult syncIncremental(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return SyncResult.empty();
        }
        String wallet = walletAddress.trim();
        SmartWalletProperties.PolymarketMonitor.ClosedPositionSync cfg = config();
        int pageSize = Math.max(1, cfg.getPageSize());
        int maxPages = Math.max(1, cfg.getMaxIncrementalPages());
        int idleThreshold = Math.max(1, cfg.getIncrementalIdlePageThreshold());

        Long highWaterMark = loadHighWaterMark(wallet);
        long started = System.currentTimeMillis();
        long timeoutMs = Math.max(10_000L, cfg.getSingleWalletTimeoutMs());

        int inserted = 0;
        int visited = 0;
        int idlePages = 0;
        String quality = QUALITY_TRUTH;
        String lastSignature = null;

        for (int page = 0; page < maxPages; page++) {
            if (System.currentTimeMillis() - started > timeoutMs) {
                log.warn("[ClosedPositionSync] incremental timeout wallet={} page={} elapsedMs={}",
                        wallet, page, System.currentTimeMillis() - started);
                quality = QUALITY_PARTIAL;
                break;
            }
            int offset = page * pageSize;
            List<PolymarketApiClient.ClosedPosition> chunk = fetchPage(wallet, pageSize, offset);
            if (chunk == null) {
                quality = QUALITY_PARTIAL;
                break;
            }
            if (chunk.isEmpty()) {
                break;
            }
            String signature = pageSignature(chunk);
            if (signature.equals(lastSignature)) {
                log.warn("[ClosedPositionSync] stuck detected wallet={} offset={} size={}",
                        wallet, offset, chunk.size());
                quality = QUALITY_STUCK;
                break;
            }
            lastSignature = signature;

            PageOutcome outcome = persistChunk(wallet, chunk, highWaterMark, true);
            visited += chunk.size();
            inserted += outcome.inserted;

            if (outcome.sentinelHit) {
                // 遇到哨兵：该页已有至少 1 条在 DB 中，或 closed_at ≤ 高水位。
                break;
            }
            if (outcome.inserted == 0) {
                idlePages++;
                if (idlePages >= idleThreshold) {
                    break;
                }
            } else {
                idlePages = 0;
            }
            if (chunk.size() < pageSize) {
                break;
            }
        }

        updateProfileSyncState(wallet, quality, false);
        log.info("[ClosedPositionSync] incremental done wallet={} visited={} inserted={} quality={} elapsedMs={}",
                wallet, visited, inserted, quality, System.currentTimeMillis() - started);
        return new SyncResult(wallet, visited, inserted, quality);
    }

    /** 首次全量或周期性复扫。不依赖高水位，翻到 chunk 空/stuck 为止。 */
    public SyncResult syncFull(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return SyncResult.empty();
        }
        String wallet = walletAddress.trim();
        SmartWalletProperties.PolymarketMonitor.ClosedPositionSync cfg = config();
        int pageSize = Math.max(1, cfg.getPageSize());
        int maxPages = Math.max(1, cfg.getMaxFullPages());
        long started = System.currentTimeMillis();
        long timeoutMs = Math.max(30_000L, cfg.getSingleWalletTimeoutMs());

        int inserted = 0;
        int visited = 0;
        String quality = QUALITY_TRUTH;
        String lastSignature = null;

        for (int page = 0; page < maxPages; page++) {
            if (System.currentTimeMillis() - started > timeoutMs) {
                log.warn("[ClosedPositionSync] full timeout wallet={} page={} elapsedMs={}",
                        wallet, page, System.currentTimeMillis() - started);
                quality = QUALITY_PARTIAL;
                break;
            }
            int offset = page * pageSize;
            List<PolymarketApiClient.ClosedPosition> chunk = fetchPage(wallet, pageSize, offset);
            if (chunk == null) {
                quality = QUALITY_PARTIAL;
                break;
            }
            if (chunk.isEmpty()) {
                break;
            }
            String signature = pageSignature(chunk);
            if (signature.equals(lastSignature)) {
                log.warn("[ClosedPositionSync] full stuck wallet={} offset={}", wallet, offset);
                quality = QUALITY_STUCK;
                break;
            }
            lastSignature = signature;

            PageOutcome outcome = persistChunk(wallet, chunk, null, false);
            visited += chunk.size();
            inserted += outcome.inserted;
            if (chunk.size() < pageSize) {
                break;
            }
        }

        updateProfileSyncState(wallet, quality, true);
        log.info("[ClosedPositionSync] full done wallet={} visited={} inserted={} quality={} elapsedMs={}",
                wallet, visited, inserted, quality, System.currentTimeMillis() - started);
        return new SyncResult(wallet, visited, inserted, quality);
    }

    /**
     * 从本地 Polymarket data-api 落盘文件回灌 closed-position 存量数据。
     *
     * 支持：
     * - /tmp/poly_api/<wallet>_closed_<offset>.json
     * - /tmp/poly_api/<wallet>_positions.json 中 redeemable=true 的已结算但未 claim 仓位
     */
    public LocalBackfillResult backfillLocalDirectory(String directory, Integer limit, boolean dryRun) {
        Path dir = Path.of(StringUtils.hasText(directory) ? directory.trim() : "/tmp/poly_api");
        int maxRows = limit == null || limit <= 0 ? Integer.MAX_VALUE : limit;
        long started = System.currentTimeMillis();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Local Polymarket data directory does not exist: " + dir);
        }

        List<LocalPositionRow> rows = loadLocalRows(dir);
        rows.sort(Comparator
                .comparing(LocalPositionRow::wallet)
                .thenComparing(row -> row.position().getClosedAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(row -> normalizeOutcome(row.position().getOutcome())));

        int visited = 0;
        int inserted = 0;
        int skipped = 0;
        int failed = 0;
        Set<String> touchedWallets = new HashSet<>();
        Set<String> seenInRun = new HashSet<>();

        for (LocalPositionRow row : rows) {
            if (visited >= maxRows) {
                break;
            }
            visited++;
            PolymarketApiClient.ClosedPosition position = row.position();
            if (!StringUtils.hasText(row.wallet()) || position == null
                    || !StringUtils.hasText(position.getMarketId())
                    || !StringUtils.hasText(position.getOutcome())
                    || position.getClosedAt() == null) {
                skipped++;
                continue;
            }
            String runKey = row.wallet() + "|" + uniqueKey(position.getMarketId(), position.getOutcome(), position.getClosedAt());
            if (!seenInRun.add(runKey)) {
                skipped++;
                continue;
            }
            if (exists(row.wallet(), position)) {
                skipped++;
                continue;
            }
            if (dryRun) {
                inserted++;
                touchedWallets.add(row.wallet());
                continue;
            }
            try {
                orderAnalysisMapper.insert(buildEntity(row.wallet(), position));
                inserted++;
                touchedWallets.add(row.wallet());
            } catch (Exception ex) {
                failed++;
                log.debug("[ClosedPositionSync] local backfill insert skipped wallet={} marketId={} outcome={} closedAt={} msg={}",
                        row.wallet(), position.getMarketId(), position.getOutcome(), position.getClosedAt(), ex.getMessage());
            }
        }

        if (!dryRun) {
            for (String wallet : touchedWallets) {
                updateProfileSyncState(wallet, QUALITY_TRUTH, true);
            }
        }
        long elapsed = System.currentTimeMillis() - started;
        log.info("[ClosedPositionSync] local backfill done dir={} dryRun={} files={} visited={} inserted={} skipped={} failed={} wallets={} elapsedMs={}",
                dir, dryRun, countLocalFiles(dir), visited, inserted, skipped, failed, touchedWallets.size(), elapsed);
        return new LocalBackfillResult(dir.toString(), dryRun, countLocalFiles(dir), touchedWallets.size(),
                visited, inserted, skipped, failed, rows.size(), elapsed);
    }

    // ------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------

    private List<PolymarketApiClient.ClosedPosition> fetchPage(String wallet, int pageSize, int offset) {
        boolean acquired = false;
        try {
            acquired = httpSemaphore.tryAcquire(30, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[ClosedPositionSync] http semaphore timeout wallet={} offset={}", wallet, offset);
                return null;
            }
            return polymarketApiClient.fetchClosedPositions(
                    wallet, pageSize, offset, SORT_BY_TIMESTAMP, SORT_DIRECTION_DESC);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            log.warn("[ClosedPositionSync] fetch failed wallet={} offset={}: {}",
                    wallet, offset, ex.getMessage());
            return null;
        } finally {
            if (acquired) {
                httpSemaphore.release();
            }
        }
    }

    private PageOutcome persistChunk(String wallet,
                                     List<PolymarketApiClient.ClosedPosition> chunk,
                                     Long highWaterMark,
                                     boolean stopOnSentinel) {
        if (chunk == null || chunk.isEmpty()) {
            return new PageOutcome(0, false);
        }
        Set<String> existingKeys = loadExistingKeys(wallet, chunk);
        int inserted = 0;
        boolean sentinelHit = false;

        for (PolymarketApiClient.ClosedPosition position : chunk) {
            if (position == null || position.getClosedAt() == null) {
                continue;
            }
            String key = uniqueKey(position.getMarketId(), position.getOutcome(), position.getClosedAt());
            boolean alreadyPresent = existingKeys.contains(key);
            boolean belowWatermark = highWaterMark != null && position.getClosedAt() <= highWaterMark;
            if (alreadyPresent || belowWatermark) {
                sentinelHit = true;
                if (stopOnSentinel) {
                    continue;
                }
            }
            if (alreadyPresent) {
                continue;
            }
            SmartWalletOrderAnalysisEntity entity = buildEntity(wallet, position);
            try {
                orderAnalysisMapper.insert(entity);
                inserted++;
            } catch (Exception ex) {
                // 唯一键冲突（并发场景）归类为已存在，不重复插入
                log.debug("[ClosedPositionSync] insert conflict wallet={} key={} msg={}",
                        wallet, key, ex.getMessage());
            }
        }
        return new PageOutcome(inserted, sentinelHit);
    }

    private Set<String> loadExistingKeys(String wallet,
                                         List<PolymarketApiClient.ClosedPosition> chunk) {
        Set<String> marketIds = new HashSet<>();
        for (PolymarketApiClient.ClosedPosition p : chunk) {
            if (p != null && StringUtils.hasText(p.getMarketId())) {
                marketIds.add(p.getMarketId().trim());
            }
        }
        if (marketIds.isEmpty()) {
            return new HashSet<>();
        }
        List<SmartWalletOrderAnalysisEntity> rows = orderAnalysisMapper.selectList(
                new LambdaQueryWrapper<SmartWalletOrderAnalysisEntity>()
                        .eq(SmartWalletOrderAnalysisEntity::getWalletAddress, wallet)
                        .eq(SmartWalletOrderAnalysisEntity::getSource, SOURCE)
                        .in(SmartWalletOrderAnalysisEntity::getMarketId, marketIds));
        Set<String> keys = new HashSet<>();
        for (SmartWalletOrderAnalysisEntity row : rows) {
            keys.add(uniqueKey(row.getMarketId(), row.getOutcome(),
                    row.getClosedAt() == null ? null : row.getClosedAt().getTime()));
        }
        return keys;
    }

    private Long loadHighWaterMark(String wallet) {
        SmartWalletProfileEntity profile = profileMapper.selectOne(
                new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .eq(SmartWalletProfileEntity::getWalletAddress, wallet)
                        .eq(SmartWalletProfileEntity::getSource, SOURCE)
                        .last("limit 1"));
        return profile == null ? null : profile.getClosedSyncMaxClosedAt();
    }

    private void updateProfileSyncState(String wallet, String quality, boolean fullRun) {
        SmartWalletProfileEntity profile = profileMapper.selectOne(
                new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .eq(SmartWalletProfileEntity::getWalletAddress, wallet)
                        .eq(SmartWalletProfileEntity::getSource, SOURCE)
                        .last("limit 1"));
        if (profile == null) {
            return;
        }
        Long maxClosedAt = orderAnalysisMapper.selectList(
                new LambdaQueryWrapper<SmartWalletOrderAnalysisEntity>()
                        .eq(SmartWalletOrderAnalysisEntity::getWalletAddress, wallet)
                        .eq(SmartWalletOrderAnalysisEntity::getSource, SOURCE)
                        .orderByDesc(SmartWalletOrderAnalysisEntity::getClosedAt)
                        .last("limit 1")).stream()
                .map(e -> e.getClosedAt() == null ? null : e.getClosedAt().getTime())
                .findFirst().orElse(null);

        Integer totalCount = Math.toIntExact(orderAnalysisMapper.selectCount(
                new LambdaQueryWrapper<SmartWalletOrderAnalysisEntity>()
                        .eq(SmartWalletOrderAnalysisEntity::getWalletAddress, wallet)
                        .eq(SmartWalletOrderAnalysisEntity::getSource, SOURCE)));

        profile.setClosedSyncMaxClosedAt(maxClosedAt);
        profile.setClosedSyncTotalCount(totalCount == null ? 0 : totalCount);
        profile.setClosedSyncDataQuality(quality);
        if (fullRun) {
            profile.setClosedSyncLastFullAt(new Date());
        }
        profile.setUpdatedAt(new Date());
        profileMapper.updateById(profile);
    }

    private SmartWalletOrderAnalysisEntity buildEntity(String wallet,
                                                       PolymarketApiClient.ClosedPosition position) {
        Date now = new Date();
        BigDecimal invested = resolveInvestedUsdc(position);
        BigDecimal realized = safe(position.getRealizedPnl()).add(safe(position.getCashPnl()));
        BigDecimal roi = invested.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : realized.divide(invested, 8, RoundingMode.HALF_UP);
        boolean isWin = realized.compareTo(BigDecimal.ZERO) > 0;
        return new SmartWalletOrderAnalysisEntity()
                .setWalletAddress(wallet)
                .setSource(SOURCE)
                .setMarketId(position.getMarketId())
                .setQuestion(position.getQuestion())
                .setOutcome(normalizeOutcome(position.getOutcome()))
                .setOpenedAt(position.getOpenedAt() == null ? null : new Date(position.getOpenedAt()))
                .setClosedAt(position.getClosedAt() == null ? null : new Date(position.getClosedAt()))
                .setMarketEndAt(null)
                .setAverageEntryPrice(safe(position.getAvgPrice()))
                .setInvestedUsdc(invested)
                .setRealizedPnlUsdc(realized)
                .setRoiRate(roi)
                .setHoldMinutes(null)
                .setExitStyle(null)
                .setExitLeadMinutes(null)
                .setIsWin(isWin)
                .setSourceConfidence(new BigDecimal("0.9000"))
                .setCreatedAt(now)
                .setUpdatedAt(now);
    }

    private BigDecimal resolveInvestedUsdc(PolymarketApiClient.ClosedPosition position) {
        if (position == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal shares = safe(position.getSizeBought());
        BigDecimal avgPrice = safe(position.getAvgPrice());
        if (shares.compareTo(BigDecimal.ZERO) > 0 && avgPrice.compareTo(BigDecimal.ZERO) > 0) {
            return shares.multiply(avgPrice).setScale(8, RoundingMode.HALF_UP);
        }
        return shares;
    }

    private boolean exists(String wallet, PolymarketApiClient.ClosedPosition position) {
        Long count = orderAnalysisMapper.selectCount(new LambdaQueryWrapper<SmartWalletOrderAnalysisEntity>()
                .eq(SmartWalletOrderAnalysisEntity::getWalletAddress, wallet)
                .eq(SmartWalletOrderAnalysisEntity::getSource, SOURCE)
                .eq(SmartWalletOrderAnalysisEntity::getMarketId, position.getMarketId())
                .eq(SmartWalletOrderAnalysisEntity::getOutcome, normalizeOutcome(position.getOutcome()))
                .eq(SmartWalletOrderAnalysisEntity::getClosedAt, new Date(position.getClosedAt())));
        return count != null && count > 0;
    }

    private List<LocalPositionRow> loadLocalRows(Path dir) {
        List<LocalPositionRow> rows = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                String filename = file.getFileName().toString();
                try {
                    if (filename.contains("_closed_")) {
                        rows.addAll(loadClosedFile(file, walletFromClosedFilename(filename)));
                    } else if (filename.endsWith("_positions.json")) {
                        rows.addAll(loadRedeemablePositionsFile(file, walletFromPositionsFilename(filename)));
                    }
                } catch (Exception ex) {
                    log.warn("[ClosedPositionSync] local backfill parse failed file={} msg={}", file, ex.getMessage());
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to scan local Polymarket data directory: " + dir, ex);
        }
        return rows;
    }

    private List<LocalPositionRow> loadClosedFile(Path file, String wallet) throws java.io.IOException {
        if (!StringUtils.hasText(wallet)) {
            return List.of();
        }
        JsonNode root = objectMapper.readTree(file.toFile());
        List<LocalPositionRow> rows = new ArrayList<>();
        if (root == null || !root.isArray()) {
            return rows;
        }
        for (JsonNode node : root) {
            Long closedAt = epochMillis(node, "timestamp", "closedAt", "redeemedAt", "endDate");
            rows.add(new LocalPositionRow(wallet, PolymarketApiClient.ClosedPosition.builder()
                    .marketId(text(node, "conditionId", "market", "marketId"))
                    .question(text(node, "question", "title"))
                    .outcome(text(node, "outcome", "outcomeName"))
                    .sizeBought(num(node, "sizeBought", "totalBought", "amount"))
                    .realizedPnl(num(node, "realizedPnl", "pnl", "profit"))
                    .cashPnl(num(node, "cashPnl"))
                    .avgPrice(num(node, "avgPrice", "averagePrice"))
                    .openedAt(epochMillis(node, "openedAt", "createdAt", "startDate"))
                    .closedAt(closedAt)
                    .build()));
        }
        return rows;
    }

    private List<LocalPositionRow> loadRedeemablePositionsFile(Path file, String wallet) throws java.io.IOException {
        if (!StringUtils.hasText(wallet)) {
            return List.of();
        }
        JsonNode root = objectMapper.readTree(file.toFile());
        List<LocalPositionRow> rows = new ArrayList<>();
        if (root == null || !root.isArray()) {
            return rows;
        }
        for (JsonNode node : root) {
            if (!node.path("redeemable").asBoolean(false)) {
                continue;
            }
            BigDecimal avgPrice = num(node, "avgPrice", "averagePrice");
            BigDecimal initialValue = num(node, "initialValue");
            BigDecimal sizeBought = sharesFromInitialValue(initialValue, avgPrice, num(node, "size", "totalBought"));
            rows.add(new LocalPositionRow(wallet, PolymarketApiClient.ClosedPosition.builder()
                    .marketId(text(node, "conditionId", "market", "marketId"))
                    .question(text(node, "question", "title"))
                    .outcome(text(node, "outcome", "outcomeName"))
                    .sizeBought(sizeBought)
                    .realizedPnl(num(node, "realizedPnl"))
                    .cashPnl(num(node, "cashPnl"))
                    .avgPrice(avgPrice)
                    .openedAt(epochMillis(node, "openedAt", "createdAt", "startDate"))
                    .closedAt(epochMillis(node, "closedAt", "redeemedAt", "timestamp", "endDate"))
                    .build()));
        }
        return rows;
    }

    private BigDecimal sharesFromInitialValue(BigDecimal initialValue, BigDecimal avgPrice, BigDecimal fallbackShares) {
        if (safe(initialValue).compareTo(BigDecimal.ZERO) > 0 && safe(avgPrice).compareTo(BigDecimal.ZERO) > 0) {
            return initialValue.divide(avgPrice, 8, RoundingMode.HALF_UP);
        }
        return fallbackShares;
    }

    private String walletFromClosedFilename(String filename) {
        int index = filename.indexOf("_closed_");
        return index <= 0 ? null : filename.substring(0, index);
    }

    private String walletFromPositionsFilename(String filename) {
        String suffix = "_positions.json";
        return filename.endsWith(suffix) ? filename.substring(0, filename.length() - suffix.length()) : null;
    }

    private int countLocalFiles(Path dir) {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path ignored : stream) {
                count++;
            }
        } catch (Exception ignored) {
            return 0;
        }
        return count;
    }

    private String text(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private BigDecimal num(JsonNode node, String... fields) {
        String value = text(node, fields);
        if (!StringUtils.hasText(value)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private Long epochMillis(JsonNode node, String... fields) {
        String value = text(node, fields);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            long parsed = new BigDecimal(value).longValue();
            return parsed < 10_000_000_000L ? parsed * 1000L : parsed;
        } catch (NumberFormatException ignored) {
            // Try ISO date below.
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
            } catch (DateTimeParseException ex) {
                return null;
            }
        }
    }

    private String pageSignature(List<PolymarketApiClient.ClosedPosition> chunk) {
        StringBuilder sb = new StringBuilder(chunk.size() * 48);
        for (PolymarketApiClient.ClosedPosition p : chunk) {
            sb.append(uniqueKey(
                    p == null ? null : p.getMarketId(),
                    p == null ? null : p.getOutcome(),
                    p == null ? null : p.getClosedAt()));
            sb.append('#');
        }
        return sb.toString();
    }

    private String uniqueKey(String marketId, String outcome, Long closedAt) {
        return defaultString(marketId) + "|" + normalizeOutcome(outcome)
                + "|" + (closedAt == null ? "NULL" : closedAt);
    }

    private String uniqueKey(String marketId, String outcome, Date closedAt) {
        return uniqueKey(marketId, outcome, closedAt == null ? null : closedAt.getTime());
    }

    private String normalizeOutcome(String outcome) {
        return StringUtils.hasText(outcome) ? outcome.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private SmartWalletProperties.PolymarketMonitor.ClosedPositionSync config() {
        return properties.getPolymarket().getClosedPositionSync();
    }

    private static int safeInt(java.util.function.Supplier<Integer> supplier, int fallback) {
        try {
            Integer v = supplier.get();
            return v == null || v <= 0 ? fallback : v;
        } catch (Exception ex) {
            return fallback;
        }
    }

    private record PageOutcome(int inserted, boolean sentinelHit) { }

    public record SyncResult(String walletAddress, int visited, int inserted, String dataQuality) {
        public static SyncResult empty() {
            return new SyncResult("", 0, 0, QUALITY_PARTIAL);
        }
    }

    public record LocalBackfillResult(String directory,
                                      boolean dryRun,
                                      int files,
                                      int wallets,
                                      int visited,
                                      int inserted,
                                      int skipped,
                                      int failed,
                                      int availableRows,
                                      long elapsedMs) {
    }

    private record LocalPositionRow(String wallet, PolymarketApiClient.ClosedPosition position) {
    }
}
