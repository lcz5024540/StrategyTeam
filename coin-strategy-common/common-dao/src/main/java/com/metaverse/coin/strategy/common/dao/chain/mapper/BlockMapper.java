package com.metaverse.coin.strategy.common.dao.chain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.dao.chain.bean.Block;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BlockMapper extends BaseMapper<Block> {
}