package com.metaverse.coin.strategy.common.service.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletMarketInfoMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFillEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeOrderEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公共市场信息服务 —— 负责 smart_wallet_market_info 表的查询、缓存与实体填充。
 * <p>
 * 所有业务 Service 在查出 order/position/fill/follow_task 后，应调用本服务的
 * {@code populate*} 方法将 marketInfo 关联对象填充到实体上，
 * 这样后续业务代码调用 {@code entity.getMarketId()} 等便捷方法就能正常工作。
 */
@Service
@Slf4j
public class SmartWalletMarketInfoService {

    private final SmartWalletMarketInfoMapper marketInfoMapper;

    /** LRU 缓存上限，超出后自动淘汰最久未访问的条目 */
    private static final int CACHE_MAX_SIZE = 10_000;

    /** 进程内 LRU 缓存，按 id 索引，带容量上限防止内存泄漏 */
    private final Map<Long, SmartWalletMarketInfoEntity> cacheById = Collections.synchronizedMap(
            new LinkedHashMap<Long, SmartWalletMarketInfoEntity>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, SmartWalletMarketInfoEntity> eldest) {
                    return size() > CACHE_MAX_SIZE;
                }
            });

    public SmartWalletMarketInfoService(SmartWalletMarketInfoMapper marketInfoMapper) {
        this.marketInfoMapper = marketInfoMapper;
    }

    // ──────────────────────────────────────────────────────────────────
    // 基础查询
    // ──────────────────────────────────────────────────────────────────

    public SmartWalletMarketInfoEntity getById(Long id) {
        if (id == null) return null;
        return cacheById.computeIfAbsent(id, marketInfoMapper::selectById);
    }

    public SmartWalletMarketInfoEntity getByMarketIdAndOutcome(String marketId, String outcome) {
        if (!StringUtils.hasText(marketId) || !StringUtils.hasText(outcome)) return null;
        List<SmartWalletMarketInfoEntity> list = marketInfoMapper.selectList(new LambdaQueryWrapper<SmartWalletMarketInfoEntity>()
                .eq(SmartWalletMarketInfoEntity::getMarketId, marketId)
                .eq(SmartWalletMarketInfoEntity::getOutcome, outcome)
                .orderByDesc(SmartWalletMarketInfoEntity::getUpdatedAt)
                .last("limit 1"));
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    /** 单次 listByMarketId / batchListByMarketIds 最大加载行数，防止历史数据堆积导致 OOM */
    private static final int MAX_LIST_ROWS = 5_000;

    public List<SmartWalletMarketInfoEntity> listByMarketId(String marketId) {
        if (!StringUtils.hasText(marketId)) {
            return List.of();
        }
        final int pageSize = 1000;
        List<SmartWalletMarketInfoEntity> rows = new java.util.ArrayList<>();
        long lastId = 0L;
        while (true) {
            List<SmartWalletMarketInfoEntity> chunk = marketInfoMapper.selectList(new LambdaQueryWrapper<SmartWalletMarketInfoEntity>()
                    .eq(SmartWalletMarketInfoEntity::getMarketId, marketId.trim())
                    .gt(SmartWalletMarketInfoEntity::getId, lastId)
                    .orderByAsc(SmartWalletMarketInfoEntity::getId)
                    .last("limit " + pageSize));
            if (chunk == null || chunk.isEmpty()) {
                break;
            }
            rows.addAll(chunk);
            for (SmartWalletMarketInfoEntity row : chunk) {
                if (row != null && row.getId() != null && row.getId() > lastId) {
                    lastId = row.getId();
                }
            }
            if (chunk.size() < pageSize || rows.size() >= MAX_LIST_ROWS) {
                if (rows.size() >= MAX_LIST_ROWS) {
                    log.warn("listByMarketId hit hard limit {} for marketId={}", MAX_LIST_ROWS, marketId);
                }
                break;
            }
        }
        rows.sort(Comparator
                .comparing(SmartWalletMarketInfoEntity::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SmartWalletMarketInfoEntity::getId, Comparator.nullsLast(Comparator.reverseOrder())));
        rows.forEach(row -> cacheById.put(row.getId(), row));
        return rows;
    }

    public Map<String, List<SmartWalletMarketInfoEntity>> batchListByMarketIds(Collection<String> marketIds) {
        if (marketIds == null || marketIds.isEmpty()) {
            return Map.of();
        }
        List<String> normalizedIds = marketIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }
        final int pageSize = 1000;
        List<SmartWalletMarketInfoEntity> rows = new java.util.ArrayList<>();
        long lastId = 0L;
        while (true) {
            List<SmartWalletMarketInfoEntity> chunk = marketInfoMapper.selectList(new LambdaQueryWrapper<SmartWalletMarketInfoEntity>()
                    .in(SmartWalletMarketInfoEntity::getMarketId, normalizedIds)
                    .gt(SmartWalletMarketInfoEntity::getId, lastId)
                    .orderByAsc(SmartWalletMarketInfoEntity::getId)
                    .last("limit " + pageSize));
            if (chunk == null || chunk.isEmpty()) {
                break;
            }
            rows.addAll(chunk);
            for (SmartWalletMarketInfoEntity row : chunk) {
                if (row != null && row.getId() != null && row.getId() > lastId) {
                    lastId = row.getId();
                }
            }
            if (chunk.size() < pageSize || rows.size() >= MAX_LIST_ROWS) {
                if (rows.size() >= MAX_LIST_ROWS) {
                    log.warn("batchListByMarketIds hit hard limit {} for {} marketIds", MAX_LIST_ROWS, normalizedIds.size());
                }
                break;
            }
        }
        rows.sort(Comparator
                .comparing(SmartWalletMarketInfoEntity::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SmartWalletMarketInfoEntity::getId, Comparator.nullsLast(Comparator.reverseOrder())));
        Map<String, List<SmartWalletMarketInfoEntity>> result = new LinkedHashMap<>();
        for (SmartWalletMarketInfoEntity row : rows) {
            cacheById.put(row.getId(), row);
            result.computeIfAbsent(row.getMarketId(), key -> new java.util.ArrayList<>()).add(row);
        }
        return result;
    }

    public Map<String, SmartWalletMarketInfoEntity> batchGetPrimaryByMarketIds(Collection<String> marketIds) {
        Map<String, List<SmartWalletMarketInfoEntity>> rowsByMarketId = batchListByMarketIds(marketIds);
        Map<String, SmartWalletMarketInfoEntity> result = new LinkedHashMap<>();
        rowsByMarketId.forEach((marketId, rows) -> rows.stream()
                .sorted(Comparator.comparing(SmartWalletMarketInfoEntity::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(SmartWalletMarketInfoEntity::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .ifPresent(row -> result.put(marketId, row)));
        return result;
    }

    /**
     * 查找仍可交易/活跃但关键展示字段不完整的市场。
     * <p>
     * 这些市场可能没有开放仓位，但仍被 open/FILLED 订单引用；若不纳入后台刷新，
     * 订单页只能退化到搜索链接，无法打开精确 Polymarket 市场页。
     */
    public List<String> listIncompleteActiveMarketIds(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<SmartWalletMarketInfoEntity> rows = marketInfoMapper.selectList(new LambdaQueryWrapper<SmartWalletMarketInfoEntity>()
                .isNotNull(SmartWalletMarketInfoEntity::getMarketId)
                .ne(SmartWalletMarketInfoEntity::getMarketId, "")
                .and(active -> active
                        .isNull(SmartWalletMarketInfoEntity::getMarketLifecycle)
                        .or().eq(SmartWalletMarketInfoEntity::getMarketLifecycle, "ACTIVE") /* 同 SmartWalletMarketStateService.LIFECYCLE_ACTIVE；该 service 仍在 job */
                        .or().eq(SmartWalletMarketInfoEntity::getClosed, false)
                        .or().eq(SmartWalletMarketInfoEntity::getAcceptingOrders, true))
                .and(incomplete -> incomplete
                        .isNull(SmartWalletMarketInfoEntity::getMarketSlug)
                        .or().eq(SmartWalletMarketInfoEntity::getMarketSlug, "")
                        .or().isNull(SmartWalletMarketInfoEntity::getTokenId)
                        .or().eq(SmartWalletMarketInfoEntity::getTokenId, "")
                        .or().isNull(SmartWalletMarketInfoEntity::getMarketEndAt)
                        .or().le(SmartWalletMarketInfoEntity::getMarketEndAt, 0L))
                .orderByAsc(SmartWalletMarketInfoEntity::getLastMetadataSyncAt)
                .orderByAsc(SmartWalletMarketInfoEntity::getUpdatedAt)
                .orderByAsc(SmartWalletMarketInfoEntity::getId)
                .last("limit " + safeLimit));
        rows.forEach(row -> cacheById.put(row.getId(), row));
        return rows.stream()
                .map(SmartWalletMarketInfoEntity::getMarketId)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    /**
     * 按 marketId + outcome 查询，不存在则自动创建一行。
     * 用于写入场景（创建 order / fill / position / follow_task 时需要关联 market_info_id）。
     */
    public SmartWalletMarketInfoEntity getOrCreate(String marketId, String outcome,
                                                    String question, String tokenId,
                                                    Integer indexSet, Long marketEndAt) {
        if (!StringUtils.hasText(marketId) || !StringUtils.hasText(outcome)) {
            throw new IllegalArgumentException("marketId and outcome are required for smart wallet market info creation.");
        }
        String normalizedMarketId = marketId.trim();
        String normalizedOutcome = outcome.trim();
        SmartWalletMarketInfoEntity existing = getByMarketIdAndOutcome(normalizedMarketId, normalizedOutcome);
        if (existing != null) {
            // 若有新字段需要补充，顺便更新
            boolean updated = false;
            if (tokenId != null && existing.getTokenId() == null) {
                existing.setTokenId(tokenId);
                updated = true;
            }
            if (indexSet != null && existing.getIndexSet() == null) {
                existing.setIndexSet(indexSet);
                updated = true;
            }
            if (marketEndAt != null && (existing.getMarketEndAt() == null || existing.getMarketEndAt() <= 0)) {
                existing.setMarketEndAt(marketEndAt);
                updated = true;
            }
            if (question != null && existing.getQuestion() == null) {
                existing.setQuestion(question);
                updated = true;
            }
            if (updated) {
                update(existing);
            }
            return existing;
        }
        SmartWalletMarketInfoEntity newInfo = new SmartWalletMarketInfoEntity()
                .setMarketId(normalizedMarketId)
                .setQuestion(question)
                .setOutcome(normalizedOutcome)
                .setTokenId(tokenId)
                .setIndexSet(indexSet)
                .setMarketEndAt(marketEndAt);
        marketInfoMapper.insert(newInfo);
        cacheById.put(newInfo.getId(), newInfo);
        return newInfo;
    }

    public void update(SmartWalletMarketInfoEntity entity) {
        if (entity == null || entity.getId() == null) {
            return;
        }
        entity.setUpdatedAt(new Date());
        marketInfoMapper.updateById(entity);
        cacheById.put(entity.getId(), entity);
    }

    /**
     * 批量按 id 加载 market_info，返回 id→entity 的 map。
     */
    public Map<Long, SmartWalletMarketInfoEntity> batchGetByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        // 从缓存中找
        Map<Long, SmartWalletMarketInfoEntity> result = new ConcurrentHashMap<>();
        List<Long> missing = ids.stream().distinct()
                .filter(id -> {
                    SmartWalletMarketInfoEntity cached = cacheById.get(id);
                    if (cached != null) {
                        result.put(id, cached);
                        return false;
                    }
                    return true;
                }).toList();
        if (!missing.isEmpty()) {
            List<SmartWalletMarketInfoEntity> fetched = marketInfoMapper.selectBatchIds(missing);
            for (SmartWalletMarketInfoEntity info : fetched) {
                cacheById.put(info.getId(), info);
                result.put(info.getId(), info);
            }
        }
        return result;
    }

    // ──────────────────────────────────────────────────────────────────
    // 批量填充便捷方法
    // ──────────────────────────────────────────────────────────────────

    /** 填充单个 Order */
    public SmartWalletOrderEntity populate(SmartWalletOrderEntity entity) {
        if (entity != null && entity.getMarketInfoId() != null && entity.getMarketInfo() == null) {
            entity.setMarketInfo(getById(entity.getMarketInfoId()));
        }
        return entity;
    }

    /** 填充单个 Position */
    public SmartWalletPositionEntity populate(SmartWalletPositionEntity entity) {
        if (entity != null && entity.getMarketInfoId() != null && entity.getMarketInfo() == null) {
            entity.setMarketInfo(getById(entity.getMarketInfoId()));
        }
        return entity;
    }

    /** 填充单个 Fill */
    public SmartWalletFillEntity populate(SmartWalletFillEntity entity) {
        if (entity != null && entity.getMarketInfoId() != null && entity.getMarketInfo() == null) {
            entity.setMarketInfo(getById(entity.getMarketInfoId()));
        }
        return entity;
    }

    /** 填充单个 FollowTask */
    public SmartWalletFollowTaskEntity populate(SmartWalletFollowTaskEntity entity) {
        if (entity != null && entity.getMarketInfoId() != null && entity.getMarketInfo() == null) {
            entity.setMarketInfo(getById(entity.getMarketInfoId()));
        }
        return entity;
    }

    /** 填充单个 TradeOrder */
    public SmartWalletTradeOrderEntity populate(SmartWalletTradeOrderEntity entity) {
        if (entity != null && entity.getMarketInfoId() != null && entity.getMarketInfo() == null) {
            entity.setMarketInfo(getById(entity.getMarketInfoId()));
        }
        return entity;
    }

    /** 批量填充 Order 列表 */
    public List<SmartWalletOrderEntity> populateOrders(List<SmartWalletOrderEntity> entities) {
        if (entities == null || entities.isEmpty()) return entities;
        Map<Long, SmartWalletMarketInfoEntity> infoMap = batchGetByIds(
                entities.stream().map(SmartWalletOrderEntity::getMarketInfoId).filter(id -> id != null).toList());
        entities.forEach(e -> { if (e.getMarketInfoId() != null) e.setMarketInfo(infoMap.get(e.getMarketInfoId())); });
        return entities;
    }

    /** 批量填充 Position 列表 */
    public List<SmartWalletPositionEntity> populatePositions(List<SmartWalletPositionEntity> entities) {
        if (entities == null || entities.isEmpty()) return entities;
        Map<Long, SmartWalletMarketInfoEntity> infoMap = batchGetByIds(
                entities.stream().map(SmartWalletPositionEntity::getMarketInfoId).filter(id -> id != null).toList());
        entities.forEach(e -> { if (e.getMarketInfoId() != null) e.setMarketInfo(infoMap.get(e.getMarketInfoId())); });
        return entities;
    }

    /** 批量填充 Fill 列表 */
    public List<SmartWalletFillEntity> populateFills(List<SmartWalletFillEntity> entities) {
        if (entities == null || entities.isEmpty()) return entities;
        Map<Long, SmartWalletMarketInfoEntity> infoMap = batchGetByIds(
                entities.stream().map(SmartWalletFillEntity::getMarketInfoId).filter(id -> id != null).toList());
        entities.forEach(e -> { if (e.getMarketInfoId() != null) e.setMarketInfo(infoMap.get(e.getMarketInfoId())); });
        return entities;
    }

    /** 批量填充 FollowTask 列表 */
    public List<SmartWalletFollowTaskEntity> populateFollowTasks(List<SmartWalletFollowTaskEntity> entities) {
        if (entities == null || entities.isEmpty()) return entities;
        Map<Long, SmartWalletMarketInfoEntity> infoMap = batchGetByIds(
                entities.stream().map(SmartWalletFollowTaskEntity::getMarketInfoId).filter(id -> id != null).toList());
        entities.forEach(e -> { if (e.getMarketInfoId() != null) e.setMarketInfo(infoMap.get(e.getMarketInfoId())); });
        return entities;
    }

    /** 批量填充 TradeOrder 列表 */
    public List<SmartWalletTradeOrderEntity> populateTradeOrders(List<SmartWalletTradeOrderEntity> entities) {
        if (entities == null || entities.isEmpty()) return entities;
        Map<Long, SmartWalletMarketInfoEntity> infoMap = batchGetByIds(
                entities.stream().map(SmartWalletTradeOrderEntity::getMarketInfoId).filter(id -> id != null).toList());
        entities.forEach(e -> { if (e.getMarketInfoId() != null) e.setMarketInfo(infoMap.get(e.getMarketInfoId())); });
        return entities;
    }

    // ──────────────────────────────────────────────────────────────────
    // 按 marketId 查询 market_info_id（用于替代旧的 LambdaQueryWrapper 条件）
    // ──────────────────────────────────────────────────────────────────

    /**
     * 查找指定 marketId 的所有 market_info_id 列表。
     * 用于替代旧的 {@code .eq(Entity::getMarketId, marketId)} 查询。
     */
    public List<Long> findIdsByMarketId(String marketId) {
        if (!StringUtils.hasText(marketId)) return List.of();
        return marketInfoMapper.selectList(new LambdaQueryWrapper<SmartWalletMarketInfoEntity>()
                        .eq(SmartWalletMarketInfoEntity::getMarketId, marketId))
                .stream().map(SmartWalletMarketInfoEntity::getId).toList();
    }

    /**
     * 查找指定 marketId + outcome 的 market_info_id。
     */
    public Long findIdByMarketIdAndOutcome(String marketId, String outcome) {
        SmartWalletMarketInfoEntity info = getByMarketIdAndOutcome(marketId, outcome);
        return info == null ? null : info.getId();
    }

    /**
     * 分页加载全部 market_info 记录（用于批量重新分类等操作）。
     */
    public List<SmartWalletMarketInfoEntity> listAll(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50_000));
        List<SmartWalletMarketInfoEntity> result = new java.util.ArrayList<>();
        long lastId = 0L;
        int pageSize = 1000;
        while (result.size() < safeLimit) {
            int batchSize = Math.min(pageSize, safeLimit - result.size());
            List<SmartWalletMarketInfoEntity> chunk = marketInfoMapper.selectList(new LambdaQueryWrapper<SmartWalletMarketInfoEntity>()
                    .gt(SmartWalletMarketInfoEntity::getId, lastId)
                    .orderByAsc(SmartWalletMarketInfoEntity::getId)
                    .last("limit " + batchSize));
            if (chunk == null || chunk.isEmpty()) {
                break;
            }
            result.addAll(chunk);
            lastId = chunk.get(chunk.size() - 1).getId();
            if (chunk.size() < batchSize) {
                break;
            }
        }
        return result;
    }

    /**
     * 清除进程内缓存（测试或手动刷新时使用）。
     */
    public void clearCache() {
        cacheById.clear();
    }
}
