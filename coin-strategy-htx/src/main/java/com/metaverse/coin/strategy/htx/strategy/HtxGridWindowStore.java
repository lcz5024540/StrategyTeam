package com.metaverse.coin.strategy.htx.strategy;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxGridWindowEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxGridWindowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 持久化移动网格窗口；无 mapper 的实例只供轻量单元测试。 */
@Service
@RequiredArgsConstructor
public class HtxGridWindowStore {
    private final HtxGridWindowMapper mapper;
    private final Map<String, HtxGridWindowEntity> memory = new ConcurrentHashMap<>();

    public HtxGridWindowEntity loadOrCreate(String symbol, BigDecimal anchor, BigDecimal stepPct,
                                            int levels, MovingGridEngine engine, LocalDateTime now) {
        HtxGridWindowEntity existing = find(symbol);
        if (existing != null) return existing;
        HtxGridWindowEntity created = engine.initial(symbol, anchor, stepPct, levels, now);
        save(created);
        return created;
    }

    public HtxGridWindowEntity find(String symbol) {
        if (mapper == null) return memory.get(symbol);
        return mapper.selectOne(new QueryWrapper<HtxGridWindowEntity>().eq("symbol", symbol));
    }

    public void save(HtxGridWindowEntity window) {
        if (mapper == null) {
            memory.put(window.getSymbol(), window);
            return;
        }
        if (window.getId() == null) mapper.insert(window);
        else mapper.updateById(window);
    }
}
